package com.gridstore.huevista.project.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ImageCleanerService#upscaleToLongestEdge}. */
class ImageCleanerUpscaleTest {

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private static int longestEdge(byte[] bytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        return Math.max(img.getWidth(), img.getHeight());
    }

    @Test
    void enlargesLongestEdgeToTargetPreservingAspect() throws Exception {
        byte[] small = jpeg(1024, 512); // 1K-ish, 2:1
        byte[] big = ImageCleanerService.upscaleToLongestEdge(small, 3840);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(big));
        assertThat(out.getWidth()).isEqualTo(3840);
        assertThat(out.getHeight()).isEqualTo(1920); // aspect ratio kept
    }

    @Test
    void leavesImageUntouchedWhenAlreadyAtOrAboveTarget() throws Exception {
        byte[] already4k = jpeg(3840, 2160);
        byte[] result = ImageCleanerService.upscaleToLongestEdge(already4k, 3840);
        assertThat(result).isSameAs(already4k);
        assertThat(longestEdge(result)).isEqualTo(3840);
    }

    @Test
    void disabledWhenTargetIsZeroOrNegative() throws Exception {
        byte[] small = jpeg(800, 600);
        assertThat(ImageCleanerService.upscaleToLongestEdge(small, 0)).isSameAs(small);
        assertThat(ImageCleanerService.upscaleToLongestEdge(small, -1)).isSameAs(small);
    }

    @Test
    void returnsOriginalWhenBytesAreNotAnImage() {
        byte[] garbage = "not an image".getBytes();
        assertThat(ImageCleanerService.upscaleToLongestEdge(garbage, 3840)).isSameAs(garbage);
    }
}
