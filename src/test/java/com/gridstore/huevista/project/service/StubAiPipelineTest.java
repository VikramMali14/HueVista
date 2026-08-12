package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.ClaudeVisionService;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.Project;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the TESTING-ONLY stub pipeline: the fake colour-coded mask it draws
 * (three vertical RED|GREEN|BLUE stripes) and the fact that switching it on
 * takes the paid Replicate mask call out of the segmentation path entirely.
 */
class StubAiPipelineTest {

    private final StubAiPipeline stub = new StubAiPipeline();

    private void enableStub() {
        ReflectionTestUtils.setField(stub, "enabled", true);
    }

    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void drawsThreeEqualVerticalStripesInRedGreenBlue() throws Exception {
        BufferedImage img = read(stub.colorCodedMask(300, 90));

        assertThat(img.getWidth()).isEqualTo(300);
        assertThat(img.getHeight()).isEqualTo(90);
        // Vertical means each stripe holds one colour down the FULL height —
        // sample top, middle and bottom of each third.
        for (int y : new int[]{0, 45, 89}) {
            assertThat(new Color(img.getRGB(50, y))).isEqualTo(Color.RED);
            assertThat(new Color(img.getRGB(150, y))).isEqualTo(Color.GREEN);
            assertThat(new Color(img.getRGB(250, y))).isEqualTo(Color.BLUE);
        }
    }

    @Test
    void leavesNoUnpaintedColumnWhenWidthIsNotDivisibleByThree() throws Exception {
        // An unpainted column would read as black in the split — an unassigned
        // seam between two regions, which renders as an unpainted stripe.
        BufferedImage img = read(stub.colorCodedMask(101, 10));

        for (int x = 0; x < 101; x++) {
            assertThat(new Color(img.getRGB(x, 5)))
                    .as("column %d", x)
                    .isIn(Color.RED, Color.GREEN, Color.BLUE);
        }
    }

    @Test
    void splitsIntoAllThreePaintableCategories() throws Exception {
        Map<String, byte[]> parts =
                MaskProcessor.splitColorCodedMask(stub.colorCodedMask(300, 90), 2000, true);

        // The whole point of the stub: main wall, accent wall and trim on every
        // run, so all three region features have something to exercise.
        assertThat(parts.keySet()).containsExactlyInAnyOrder("main", "accent", "trim");
    }

    @Test
    void segmentationUsesTheStubMaskAndNeverCallsReplicate() throws Exception {
        enableStub();
        ProjectRepository projects = mock(ProjectRepository.class);
        RegionRepository regions = mock(RegionRepository.class);
        StorageService storage = mock(StorageService.class);
        ReplicateMaskSegmenter segmenter = mock(ReplicateMaskSegmenter.class);
        SegmentationService service = new SegmentationService(
                projects, regions, storage, mock(RestTemplate.class), segmenter,
                mock(ImageCleanerService.class), stub, mock(ImageRepository.class),
                mock(ClaudeVisionService.class), mock(BillingService.class),
                mock(CustomerAccessCodeRepository.class),
                mock(ProjectBillingResolver.class));
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 2);
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        // No cleaned canvas (the stub skips cleaning), so the ORIGINAL photo
        // sizes the masks — exactly the shape a real stub run has.
        byte[] original = png(new BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, original, 300, 150);

        assertThat(ok).isTrue();
        // Not even isConfigured() — the stub path never touches Replicate, so
        // this flow works with no REPLICATE_API_TOKEN at all.
        verifyNoInteractions(segmenter);

        ArgumentCaptor<Region> saved = ArgumentCaptor.forClass(Region.class);
        verify(regions, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Region::getCategory)
                .containsExactly(RegionCategory.MAIN_WALL, RegionCategory.ACCENT_WALL,
                        RegionCategory.TRIM);
    }

    @Test
    void storedMasksMatchTheCanvasSize() throws Exception {
        enableStub();
        ProjectRepository projects = mock(ProjectRepository.class);
        RegionRepository regions = mock(RegionRepository.class);
        StorageService storage = mock(StorageService.class);
        SegmentationService service = new SegmentationService(
                projects, regions, storage, mock(RestTemplate.class),
                mock(ReplicateMaskSegmenter.class), mock(ImageCleanerService.class),
                stub, mock(ImageRepository.class), mock(ClaudeVisionService.class),
                mock(BillingService.class), mock(CustomerAccessCodeRepository.class),
                mock(ProjectBillingResolver.class));
        ReflectionTestUtils.setField(service, "autoMaskAttempts", 1);
        when(storage.store(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("masks/key.png");
        when(projects.getReferenceById("p1")).thenReturn(mock(Project.class));

        byte[] original = png(new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB));

        boolean ok = service.tryColorCodedSegmentation(
                "p1", "u1", "http://img", ImageType.OUTDOOR, null, original, 320, 200);

        assertThat(ok).isTrue();
        // Raw colour-coded mask first (admin mask viewer), then the three region
        // masks — all at the photo's own size, so the frontend overlays them
        // on the uncleaned canvas without any stretch.
        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage, times(4)).store(stored.capture(), anyString(), anyString(), anyString());
        for (byte[] mask : stored.getAllValues()) {
            BufferedImage img = read(mask);
            assertThat(img.getWidth()).isEqualTo(320);
            assertThat(img.getHeight()).isEqualTo(200);
        }
    }
}
