package com.gridstore.huevista.project.service;

import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the maintenance re-snap over stored masks: a misregistered stored
 * mask comes back snapped to the canvas's real edge under a NEW key, the
 * region row is repointed, and foreign (http) mask references are skipped.
 */
class MaskResnapServiceTest {

    private static final int W = 160;
    private static final int H = 80;

    /** Wall/sky canvas with the real edge at x = 80. */
    private static byte[] canvasPng() throws Exception {
        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                canvas.setRGB(x, y, x < 80 ? 0xD9CCB2 : 0x8CA6E6);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    /** Binary mask whose boundary overshoots the canvas edge by 3px. */
    private static byte[] misregisteredMaskPng() throws Exception {
        BufferedImage mask = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < 83; x++) mask.setRGB(x, y, 0xFFFFFF);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(mask, "png", out);
        return out.toByteArray();
    }

    @Test
    void resnapsStoredMaskAndRepointsTheRegion() throws Exception {
        ProjectRepository projects = mock(ProjectRepository.class);
        RegionRepository regions = mock(RegionRepository.class);
        StorageService storage = mock(StorageService.class);
        MaskResnapService service = new MaskResnapService(projects, regions, storage);

        Region region = Region.builder()
                .label("Main Wall").category(RegionCategory.MAIN_WALL)
                .maskUrl("user-1/old-mask.png").maskData("user-1/old-mask.png")
                .build();
        when(regions.findAutoRegionsByProjectId("p1")).thenReturn(List.of(region));
        when(projects.findCleanedImageKeyById("p1")).thenReturn(Optional.of("user-1/cleaned.jpg"));
        when(storage.load("user-1/cleaned.jpg")).thenReturn(canvasPng());
        when(storage.load("user-1/old-mask.png")).thenReturn(misregisteredMaskPng());
        when(storage.store(any(byte[].class), eq("user-1"), anyString(), eq("image/png")))
                .thenReturn("user-1/new-mask.png");

        MaskResnapService.ResnapSummary summary = service.resnapProject("p1");

        assertThat(summary.regionsResnapped()).isEqualTo(1);
        assertThat(summary.failures()).isZero();
        assertThat(region.getMaskUrl()).isEqualTo("user-1/new-mask.png");
        assertThat(region.getMaskData()).isEqualTo("user-1/new-mask.png");
        verify(regions).save(region);

        // The stored bytes are genuinely snapped: the 3px overshoot onto the
        // sky is gone, the wall side is still covered.
        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(stored.capture(), eq("user-1"), anyString(), eq("image/png"));
        BufferedImage snapped = ImageIO.read(new ByteArrayInputStream(stored.getValue()));
        int y = H / 2;
        assertThat(snapped.getRGB(82, y) & 0xff).isLessThan(128);
        assertThat(snapped.getRGB(77, y) & 0xff).isGreaterThan(127);
    }

    @Test
    void skipsForeignUrlMasksAndProjectsWithoutACanvas() throws Exception {
        ProjectRepository projects = mock(ProjectRepository.class);
        RegionRepository regions = mock(RegionRepository.class);
        StorageService storage = mock(StorageService.class);
        MaskResnapService service = new MaskResnapService(projects, regions, storage);

        // Foreign URL (legacy SAM output) — must not be touched.
        Region foreign = Region.builder()
                .label("Wall").category(RegionCategory.MAIN_WALL)
                .maskUrl("https://replicate.delivery/some/mask.png")
                .build();
        when(regions.findAutoRegionsByProjectId("p1")).thenReturn(List.of(foreign));
        when(projects.findCleanedImageKeyById("p1")).thenReturn(Optional.of("user-1/cleaned.jpg"));
        when(storage.load("user-1/cleaned.jpg")).thenReturn(canvasPng());

        MaskResnapService.ResnapSummary summary = service.resnapProject("p1");
        assertThat(summary.regionsSkipped()).isEqualTo(1);
        assertThat(summary.regionsResnapped()).isZero();
        verify(regions, never()).save(any());

        // No cleaned canvas — all regions skipped, nothing loaded or saved.
        Region stored = Region.builder()
                .label("Wall").category(RegionCategory.MAIN_WALL)
                .maskUrl("user-2/mask.png")
                .build();
        when(regions.findAutoRegionsByProjectId("p2")).thenReturn(List.of(stored));
        when(projects.findCleanedImageKeyById("p2")).thenReturn(Optional.empty());

        MaskResnapService.ResnapSummary noCanvas = service.resnapProject("p2");
        assertThat(noCanvas.regionsSkipped()).isEqualTo(1);
        assertThat(noCanvas.regionsResnapped()).isZero();
    }
}
