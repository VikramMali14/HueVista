package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.image.model.HouseType;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.SceneAnalysis;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.ClaudeVisionService;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.maskreport.service.MaskReportService;
import com.gridstore.huevista.project.model.FailureStage;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the colour-coded auto-segmentation path: a dud generation
 * (no usable main wall) is retried with a fresh model call, and — critically
 * — persists NOTHING, so a failed run can't leave orphan accent/trim rows on
 * a FAILED project.
 */
class SegmentationServiceTest {

    private static final int W = 200;
    private static final int H = 100;

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final ReplicateMaskSegmenter segmenter = mock(ReplicateMaskSegmenter.class);
    /** Defaults to isEnabled()=false, i.e. the real Replicate path, for every
     *  test that doesn't explicitly turn the testing stub on. */
    private final StubAiPipeline stubAiPipeline = mock(StubAiPipeline.class);
    private final ImageCleanerService cleaner = mock(ImageCleanerService.class);
    private final ImageRepository images = mock(ImageRepository.class);
    private final ClaudeVisionService vision = mock(ClaudeVisionService.class);
    /** Real, not mocked: its whole job is one small decision, and a mock of it would
     *  only ever assert that the tests agree with themselves. Left at its default
     *  (NONE), so nothing here simulates a failure unless it says so. */
    private final AiFailureSimulator failureSimulator = new AiFailureSimulator();
    private final MaskReportService maskReports = mock(MaskReportService.class);
    private final SegmentationService service = new SegmentationService(
            projects, regions, storage, mock(RestTemplate.class), segmenter,
            cleaner, stubAiPipeline, failureSimulator, images, vision,
            mock(BillingService.class), mock(CustomerAccessCodeRepository.class),
            mock(ProjectBillingResolver.class), maskReports);

    /** Colour-coded model output WITH a usable main wall: red block (12000 px)
     *  plus a blue trim block (4000 px), rest black. */
    private static byte[] goodCodedPng() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        fill(img, Color.RED, 0, 0, 120, H);
        fill(img, Color.BLUE, 160, 0, 40, H);
        return png(img);
    }

    /** Dud output: trim only — plenty of blue but NO red main wall anywhere. */
    private static byte[] dudCodedPng() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        fill(img, Color.BLUE, 0, 0, W, H);
        return png(img);
    }

    private static void fill(BufferedImage img, Color c, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                img.setRGB(x, y, c.getRGB());
            }
        }
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void retriesAfterDudGenerationAndPersistsNothingFromIt() throws Exception {
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 2);
        when(segmenter.isConfigured()).thenReturn(true);
        // First generation is a dud (trim but no main wall); second is usable.
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(dudCodedPng()))
                .thenReturn(Optional.of(goodCodedPng()));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isTrue();
        verify(segmenter, times(2)).generateColorCodedMask(anyString(), any(), any());

        // Only the GOOD attempt's regions were saved — the dud's blue trim was
        // never persisted even though it cleared the trim size threshold.
        ArgumentCaptor<Region> saved = ArgumentCaptor.forClass(Region.class);
        verify(regions, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Region::getCategory)
                .containsExactly(RegionCategory.MAIN_WALL, RegionCategory.TRIM);
    }

    @Test
    void failsWithoutPersistingAnythingWhenEveryAttemptIsADud() throws Exception {
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 2);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(dudCodedPng()));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isFalse();
        verify(segmenter, times(2)).generateColorCodedMask(anyString(), any(), any());
        verify(regions, never()).save(any());
        verify(storage, never()).store(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    void eachModelGetsItsTwoTriesBeforeTheSiblingTierIsAsked() throws Exception {
        // The mask chain, and the shape that distinguishes it from the cleaner's. A dud
        // mask is usually non-determinism rather than a busy queue — the model answered,
        // the answer was unusable — so a second roll of the SAME model is the cheapest
        // thing to try. Only after two duds is the model itself the suspect, and the
        // sibling tier gets its turn.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 2);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.modelChain())
                .thenReturn(List.of("google/nano-banana-pro", "google/nano-banana-2"));
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(dudCodedPng()))
                .thenReturn(Optional.of(dudCodedPng()))
                .thenReturn(Optional.of(dudCodedPng()))
                .thenReturn(Optional.of(goodCodedPng()));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isTrue();
        ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);
        verify(segmenter, times(4)).generateColorCodedMask(anyString(), any(), asked.capture());
        assertThat(asked.getAllValues()).containsExactly(
                "google/nano-banana-pro", "google/nano-banana-pro",
                "google/nano-banana-2", "google/nano-banana-2");
    }

    @Test
    void theWholeChainProducingDudsGivesUpRatherThanLoopingForever() throws Exception {
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 2);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.modelChain())
                .thenReturn(List.of("google/nano-banana-pro", "google/nano-banana-2"));
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(dudCodedPng()));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        // False, not an exception: the caller hands the cleaned canvas over for
        // hand-marked walls and files the report itself.
        assertThat(ok).isFalse();
        verify(segmenter, times(4)).generateColorCodedMask(anyString(), any(), any());
        verify(regions, never()).save(any());
    }

    @Test
    void anAdminPinCollapsesTheMaskChainToThatOneModel() throws Exception {
        // Same reasoning as the cleaner's override: a usable mask quietly produced by
        // the sibling tier would answer a question nobody asked, and afterwards nothing
        // distinguishes it from one the pinned model made.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 2);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.modelChain())
                .thenReturn(List.of("google/nano-banana-pro", "google/nano-banana-2"));
        when(projects.findMaskModelById("p1")).thenReturn(Optional.of("black-forest-labs/flux-2-max"));
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(dudCodedPng()));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isFalse();
        ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);
        verify(segmenter, times(2)).generateColorCodedMask(anyString(), any(), asked.capture());
        assertThat(asked.getAllValues())
                .containsOnly("black-forest-labs/flux-2-max");
    }

    @Test
    void singleAttemptConfigKeepsOldSingleShotBehaviour() throws Exception {
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(dudCodedPng()));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isFalse();
        verify(segmenter, times(1)).generateColorCodedMask(anyString(), any(), any());
    }

    @Test
    void originalPhotoSizesTheStoredMasksWhenNoCleanedCanvasExists() throws Exception {
        // Cleaner disabled/failed: the ORIGINAL photo is the canvas the
        // frontend renders on, so the stored masks are resized to ITS
        // aspect and resolution rather than the model's output size.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(goodCodedPng()));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        BufferedImage original = new BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB);
        fill(original, Color.WHITE, 0, 0, 300, 150);

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, png(original), W, H);

        assertThat(ok).isTrue();
        // Three blobs stored: the raw colour-coded mask first (diagnostics for
        // the admin mask viewer), then the resized main + trim region masks.
        ArgumentCaptor<byte[]> maskBytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage, times(3)).store(maskBytes.capture(), anyString(), anyString(), anyString());
        BufferedImage storedMain = ImageIO.read(new ByteArrayInputStream(maskBytes.getAllValues().get(1)));
        assertThat(storedMain.getWidth()).isEqualTo(300);
        assertThat(storedMain.getHeight()).isEqualTo(150);
    }

    /** Coded image where the model left the accent wall WHITE and that white
     *  blob touches the top edge of the frame. */
    private static byte[] topTouchingWhiteAccentPng() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        fill(img, Color.RED, 0, 0, 100, H);        // main
        fill(img, Color.WHITE, 110, 0, 80, 90);    // accent left white, touches top
        return png(img);
    }

    @Test
    void interiorSceneSalvagesATopTouchingWhiteWall() throws Exception {
        // Indoors there is no sky, so the white-salvage sky filter must be off:
        // a wall reaching the top of a cropped photo is still adopted as accent.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(topTouchingWhiteAccentPng()));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.INDOOR, null, null, W, H);

        assertThat(ok).isTrue();
        ArgumentCaptor<Region> saved = ArgumentCaptor.forClass(Region.class);
        verify(regions, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Region::getCategory)
                .containsExactly(RegionCategory.MAIN_WALL, RegionCategory.ACCENT_WALL);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  The clean gate: no cleaned canvas, no masks
    // ────────────────────────────────────────────────────────────────────────

    /** Wires up the repository lookups segmentAsync makes before the clean step. */
    private UploadedImage stubProjectForRun(ImageType type) {
        UploadedImage image = UploadedImage.builder()
                .id("i1").storageKey("orig.jpg").imageType(type).width(W).height(H)
                .build();
        when(projects.findUserIdById("p1")).thenReturn(Optional.of("u1"));
        when(projects.findAccessCodeIdById("p1")).thenReturn(Optional.empty());
        when(projects.findImageIdById("p1")).thenReturn(Optional.of("i1"));
        when(projects.findSkipImageCleanById("p1")).thenReturn(Optional.of(false));
        when(projects.findMaskModeById("p1")).thenReturn(Optional.of("AUTO"));
        when(projects.findById("p1")).thenReturn(Optional.of(new Project()));
        when(images.findById("i1")).thenReturn(Optional.of(image));
        return image;
    }

    @Test
    void aFailedCleanNeverReachesTheMaskModel() {
        // The whole point of the gate: masks are generated FROM the cleaned canvas, so
        // when every cleaning provider declines there is nothing correct to generate
        // them from. Running the mask model anyway would spend a second generation to
        // produce regions aligned to a canvas the studio doesn't display.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        verify(segmenter, never()).generateColorCodedMask(anyString(), any(), any());
        verify(regions, never()).save(any());

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project failed = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(failed.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(failed.getFailureStage()).isEqualTo(FailureStage.CLEAN);
        // The reason is what the studio shows, so it has to point at the one thing the
        // user can actually do. Four models across two families declining inside a few
        // minutes is a statement about capacity, not about this photo — so it says the
        // system is loaded and to come back, and does NOT ask them to report a picture
        // there is nothing wrong with.
        assertThat(failed.getFailureReason())
                .isEqualTo(ImageCleanerService.SYSTEM_UNDER_LOAD)
                .contains("try again in a few minutes");
    }

    @Test
    void aRunWithTheCleanerOffStillMasksTheOriginalPhoto() throws Exception {
        // The gate is about a clean that FAILED, not about one that was never asked
        // for. With the cleaner disabled (or an admin's cleanImage=false), masking the
        // original photo is the deliberate behaviour and must survive.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(false);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(goodCodedPng()));
        when(storage.load("orig.jpg")).thenReturn(png(new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        service.segmentAsync("p1", "http://img");

        verify(segmenter, times(1)).generateColorCodedMask(anyString(), any(), any());
    }

    // ────────────────────────────────────────────────────────────────────────
    //  The mask stage: empty walls are NOT a failed project
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void anEmptyMaskKeepsTheCleanedProjectAndReportsItselfInstead() throws Exception {
        // The expensive half succeeded — there is a cleaned, repainted photo the user
        // paid for — so a mask model that finds nothing must not throw that away. The
        // project finishes SEGMENTED with no regions (what a MANUAL run looks like) and
        // the pipeline files the report, because a user holding a working room never will.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("cleaned/key.jpg");
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any())).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        verify(regions, never()).save(any());
        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(ProjectStatus.SEGMENTED);
        assertThat(last.isAutoMaskFailed()).isTrue();
        // Not a failure, so nothing that describes one may be left behind: the studio
        // reads failureStage to decide whether there is anything to open at all.
        assertThat(last.getFailureStage()).isNull();
        assertThat(last.getFailureReason()).isNull();
        verify(maskReports).reportAutoMaskFailure("p1");
    }

    @Test
    void aReportThatCannotBeFiledStillLeavesTheUserTheirCleanedRoom() throws Exception {
        // The report is a best-effort side errand. A mail server or a database hiccup
        // must never undo a run that already finished correctly — the customer standing
        // at the counter loses their photo over a problem that is entirely ours.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("cleaned/key.jpg");
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any())).thenReturn(Optional.empty());
        when(maskReports.reportAutoMaskFailure("p1")).thenThrow(new RuntimeException("inbox down"));

        service.segmentAsync("p1", "http://img");

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(ProjectStatus.SEGMENTED);
        assertThat(last.isAutoMaskFailed()).isTrue();
    }

    @Test
    void aMaskModelThatIsSwitchedOFFHandsOverWithoutFilingAReport() throws Exception {
        // Same outcome for the user — a cleaned canvas with the walls to mark — but
        // nothing to report: a report asks an admin to look at what a model did with a
        // photo, and no model looked at it. One line of configuration would otherwise
        // put a row in the queue for every project that runs under it.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("cleaned/key.jpg");
        when(segmenter.isConfigured()).thenReturn(false);

        service.segmentAsync("p1", "http://img");

        verifyNoInteractions(maskReports);
        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(ProjectStatus.SEGMENTED);
        assertThat(last.isAutoMaskFailed()).isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Simulated failures (the ADMIN testing knob)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void aSimulatedMaskFailureNeverCallsTheModelAndTakesTheHandOverPath() throws Exception {
        // The point of the knob: reach the hand-over path on demand, without paying
        // for a generation and without waiting for Nano Banana to have a bad day.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        stubProjectForRun(ImageType.OUTDOOR);
        when(projects.findSimulatedFailureById("p1")).thenReturn(Optional.of("MASK"));
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("cleaned/key.jpg");

        service.segmentAsync("p1", "http://img");

        // The CLEAN still ran for real — only the simulated half is withheld, which is
        // what makes this a rehearsal of "cleaned but no walls" rather than of nothing.
        verify(cleaner).cleanImage(anyString(), any(), any(), any(), any());
        verify(segmenter, never()).generateColorCodedMask(anyString(), any(), any());
        verify(maskReports).reportAutoMaskFailure("p1");
        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(ProjectStatus.SEGMENTED);
        assertThat(last.isAutoMaskFailed()).isTrue();
    }

    @Test
    void aSimulatedCleanFailureFailsTheRunEvenWithTheCleanerSwitchedOff() {
        // Simulating the clean failure has to work on a box where the cleaner isn't
        // configured — that is exactly the box someone tests on. Otherwise the knob
        // would quietly do nothing in the one place it is needed.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        stubProjectForRun(ImageType.OUTDOOR);
        when(projects.findSimulatedFailureById("p1")).thenReturn(Optional.of("CLEAN"));
        when(cleaner.isAvailable()).thenReturn(false);

        service.segmentAsync("p1", "http://img");

        verifyNoInteractions(segmenter);
        verify(cleaner, never()).cleanImage(anyString(), any(), any(), any(), any());
        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(last.getFailureStage()).isEqualTo(FailureStage.CLEAN);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Scene detection
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void anUnclassifiedPhotoIsClassifiedBeforeTheModelsAreAsked() throws Exception {
        // Every guest upload arrives UNKNOWN (the kiosk endpoint skips classification),
        // and UNKNOWN used to mean "treat as exterior" at four separate decisions —
        // which is how an interior room got a facade's treatment.
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        UploadedImage image = stubProjectForRun(ImageType.UNKNOWN);
        when(storage.load("orig.jpg")).thenReturn(png(new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)));
        when(vision.classifyStored(any(byte[].class))).thenReturn(ImageType.INDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), eq(ImageType.INDOOR), any(), any(), any())).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        // Asked about the right scene, and the answer is written back so a re-run of
        // this project doesn't pay for the same classification again.
        verify(cleaner).cleanImage(anyString(), eq(ImageType.INDOOR), any(), any(), any());
        assertThat(image.getImageType()).isEqualTo(ImageType.INDOOR);
        verify(images).save(image);
    }

    @Test
    void anAlreadyClassifiedPhotoIsNotSentToTheClassifierAgain() {
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        verifyNoInteractions(vision);
    }

    @Test
    void exteriorSceneRejectsATopTouchingWhiteBlobAsSky() throws Exception {
        // Same image, OUTDOOR scene: the top-touching white blob is treated as
        // sky and never becomes a paintable accent region.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any(), any()))
                .thenReturn(Optional.of(topTouchingWhiteAccentPng()));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isTrue();
        ArgumentCaptor<Region> saved = ArgumentCaptor.forClass(Region.class);
        verify(regions, times(1)).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo(RegionCategory.MAIN_WALL);
    }

    // ── Looking at the photo ─────────────────────────────────────────────────

    /**
     * A run that says nothing about the analysis still gets one.
     *
     * <p>This flag used to be opt-in: a tickbox on the confirm step, and before that an
     * admin knob, so the code read {@code TRUE.equals(...)} and a null column meant no.
     * It is not a question any more — looking at the photo properly is what a run does —
     * and the way that fails quietly is for null to keep meaning no: a guest at a kiosk
     * sends no options at all, so the walk-in would get a blinder clean than the shop's
     * own project and nothing on any screen would say so.
     */
    @Test
    void anUnsetAnalyseFlagStillLooksAtThePhoto() throws Exception {
        ProjectRepository.CleanOptionsView knobs = cleanOptions(null);
        when(projects.findCleanOptionsById("p1")).thenReturn(Optional.of(knobs));
        when(storage.load("rooms/p1.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(vision.analyseStored(any())).thenReturn(
                new SceneAnalysis(ImageType.INDOOR, HouseType.BATHROOM, "#EEE", "Chalk", null));

        UploadedImage image = new UploadedImage();
        image.setStorageKey("rooms/p1.jpg");

        ImageCleanerService.PromptOptions options = ReflectionTestUtils.invokeMethod(
                service, "resolvePromptOptions", image, "p1", ImageType.INDOOR);

        verify(vision).analyseStored(any());
        // And what it found reached the prompt, rather than being paid for and dropped.
        assertThat(options.houseType()).isEqualTo(HouseType.BATHROOM);
    }

    /** The one caller that can still switch it off: an explicit false on the row. */
    @Test
    void anExplicitFalseSkipsTheLook() throws Exception {
        ProjectRepository.CleanOptionsView knobs = cleanOptions(false);
        when(projects.findCleanOptionsById("p1")).thenReturn(Optional.of(knobs));

        UploadedImage image = new UploadedImage();
        image.setStorageKey("rooms/p1.jpg");

        ImageCleanerService.PromptOptions options = ReflectionTestUtils.invokeMethod(
                service, "resolvePromptOptions", image, "p1", ImageType.INDOOR);

        verifyNoInteractions(vision);
        assertThat(options.houseType()).isEqualTo(HouseType.UNKNOWN);
    }

    /** A project row with nothing but the analyse flag set — the other three left null. */
    private static ProjectRepository.CleanOptionsView cleanOptions(Boolean analysePhoto) {
        ProjectRepository.CleanOptionsView view = mock(ProjectRepository.CleanOptionsView.class);
        when(view.getAnalysePhoto()).thenReturn(analysePhoto);
        return view;
    }
}
