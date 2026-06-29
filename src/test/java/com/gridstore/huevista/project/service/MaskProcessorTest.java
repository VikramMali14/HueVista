package com.gridstore.huevista.project.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MaskProcessor#splitColorCodedMask}, focused on the
 * distinct-hue scheme that maps the Nano Banana color-coded mask to paintable
 * categories — including the secondary hues (yellow/cyan/magenta) added for
 * ceiling, door and window.
 */
class MaskProcessorTest {

    private static final int BAND = 10;          // px per colour band
    private static final int HEIGHT = 8;
    private static final int MIN_PIXELS = 20;    // each band has BAND*HEIGHT = 80 px

    /** Builds a horizontal strip of solid-colour bands, one per supplied colour. */
    private static byte[] strip(Color... bands) throws Exception {
        BufferedImage img = new BufferedImage(BAND * bands.length, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < bands.length; i++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < BAND; x++) {
                    img.setRGB(i * BAND + x, y, bands[i].getRGB());
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** True when the centre of the {@code bandIndex}-th band is white in the mask. */
    private static boolean bandIsForeground(byte[] maskPng, int bandIndex) throws Exception {
        BufferedImage mask = ImageIO.read(new ByteArrayInputStream(maskPng));
        int rgb = mask.getRGB(bandIndex * BAND + BAND / 2, HEIGHT / 2);
        return (rgb & 0xff) > 127; // grayscale: low byte is the value
    }

    @Test
    void splitsAllSixCategoriesFromDistinctHues() throws Exception {
        // Bands: red, green, blue, yellow, cyan, magenta, black
        byte[] coded = strip(Color.RED, Color.GREEN, Color.BLUE,
                Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.BLACK);

        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);

        assertThat(parts).containsOnlyKeys("main", "accent", "trim", "ceiling", "door", "window");

        // Each category is white in its own band and nowhere else.
        assertThat(bandIsForeground(parts.get("main"), 0)).isTrue();      // red
        assertThat(bandIsForeground(parts.get("accent"), 1)).isTrue();    // green
        assertThat(bandIsForeground(parts.get("trim"), 2)).isTrue();      // blue
        assertThat(bandIsForeground(parts.get("ceiling"), 3)).isTrue();   // yellow
        assertThat(bandIsForeground(parts.get("door"), 4)).isTrue();      // cyan
        assertThat(bandIsForeground(parts.get("window"), 5)).isTrue();    // magenta

        // No cross-contamination: e.g. the door (cyan) mask must not claim the
        // accent (green) or window (magenta) bands.
        assertThat(bandIsForeground(parts.get("door"), 1)).isFalse();
        assertThat(bandIsForeground(parts.get("door"), 5)).isFalse();
        assertThat(bandIsForeground(parts.get("ceiling"), 0)).isFalse();
    }

    @Test
    void omitsCategoriesBelowThreshold() throws Exception {
        // Only a red band; everything else absent.
        byte[] coded = strip(Color.RED, Color.BLACK);
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);
        assertThat(parts).containsOnlyKeys("main");
    }
}
