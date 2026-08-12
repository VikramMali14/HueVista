package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.ClaudeVisionService;
import com.gridstore.huevista.image.service.StorageService;
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
    private final SegmentationService service = new SegmentationService(
            projects, regions, storage, mock(RestTemplate.class), segmenter,
            cleaner, stubAiPipeline, images, vision,
            mock(BillingService.class), mock(CustomerAccessCodeRepository.class),
            mock(ProjectBillingResolver.class));

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
        when(segmenter.generateColorCodedMask(anyString(), any()))
                .thenReturn(Optional.of(dudCodedPng()))
                .thenReturn(Optional.of(goodCodedPng()));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isTrue();
        verify(segmenter, times(2)).generateColorCodedMask(anyString(), any());

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
        when(segmenter.generateColorCodedMask(anyString(), any()))
                .thenReturn(Optional.of(dudCodedPng()));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isFalse();
        verify(segmenter, times(2)).generateColorCodedMask(anyString(), any());
        verify(regions, never()).save(any());
        verify(storage, never()).store(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    void singleAttemptConfigKeepsOldSingleShotBehaviour() throws Exception {
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any()))
                .thenReturn(Optional.of(dudCodedPng()));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, null, W, H);

        assertThat(ok).isFalse();
        verify(segmenter, times(1)).generateColorCodedMask(anyString(), any());
    }

    @Test
    void originalPhotoSizesTheStoredMasksWhenNoCleanedCanvasExists() throws Exception {
        // Cleaner disabled/failed: the ORIGINAL photo is the canvas the
        // frontend renders on, so the stored masks are resized to ITS
        // aspect and resolution rather than the model's output size.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any()))
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
        when(segmenter.generateColorCodedMask(anyString(), any()))
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
        when(cleaner.cleanImage(anyString(), any())).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        verify(segmenter, never()).generateColorCodedMask(anyString(), any());
        verify(regions, never()).save(any());

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projects, atLeastOnce()).save(saved.capture());
        Project failed = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(failed.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(failed.getFailureStage()).isEqualTo(FailureStage.CLEAN);
        // The reason is what the studio shows and what the report carries, so it has to
        // point at the one thing the user can actually do.
        assertThat(failed.getFailureReason()).contains("report");
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
        when(cleaner.cleanImage(anyString(), any())).thenReturn(Optional.empty());
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any()))
                .thenReturn(Optional.of(goodCodedPng()));
        when(storage.load("orig.jpg")).thenReturn(png(new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)));
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        service.segmentAsync("p1", "http://img");

        verify(segmenter, times(1)).generateColorCodedMask(anyString(), any());
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
        when(cleaner.cleanImage(anyString(), eq(ImageType.INDOOR))).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        // Asked about the right scene, and the answer is written back so a re-run of
        // this project doesn't pay for the same classification again.
        verify(cleaner).cleanImage(anyString(), eq(ImageType.INDOOR));
        assertThat(image.getImageType()).isEqualTo(ImageType.INDOOR);
        verify(images).save(image);
    }

    @Test
    void anAlreadyClassifiedPhotoIsNotSentToTheClassifierAgain() {
        ReflectionTestUtils.setField(service, "replicateApiToken", "tok");
        stubProjectForRun(ImageType.OUTDOOR);
        when(cleaner.isAvailable()).thenReturn(true);
        when(cleaner.cleanImage(anyString(), any())).thenReturn(Optional.empty());

        service.segmentAsync("p1", "http://img");

        verifyNoInteractions(vision);
    }

    @Test
    void exteriorSceneRejectsATopTouchingWhiteBlobAsSky() throws Exception {
        // Same image, OUTDOOR scene: the top-touching white blob is treated as
        // sky and never becomes a paintable accent region.
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(segmenter.isConfigured()).thenReturn(true);
        when(segmenter.generateColorCodedMask(anyString(), any()))
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
}
