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
 * distinct-hue scheme that maps the Nano Banana color-coded mask to the three
 * paintable categories (main wall = red, accent wall = green, trim = blue).
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
    void splitsAllThreeCategoriesFromDistinctHues() throws Exception {
        // Bands: red, green, blue, black
        byte[] coded = strip(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK);

        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);

        assertThat(parts).containsOnlyKeys("main", "accent", "trim");

        // Each category is white in its own band and nowhere else.
        assertThat(bandIsForeground(parts.get("main"), 0)).isTrue();      // red
        assertThat(bandIsForeground(parts.get("accent"), 1)).isTrue();    // green
        assertThat(bandIsForeground(parts.get("trim"), 2)).isTrue();      // blue

        // No cross-contamination: e.g. the main (red) mask must not claim the
        // accent (green) or trim (blue) bands.
        assertThat(bandIsForeground(parts.get("main"), 1)).isFalse();
        assertThat(bandIsForeground(parts.get("main"), 2)).isFalse();
        assertThat(bandIsForeground(parts.get("accent"), 0)).isFalse();
    }

    @Test
    void omitsCategoriesBelowThreshold() throws Exception {
        // Only a red band; everything else absent.
        byte[] coded = strip(Color.RED, Color.BLACK);
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);
        assertThat(parts).containsOnlyKeys("main");
    }

    // ---------------------------------------------------------------------
    // resizeBinarySmooth
    // ---------------------------------------------------------------------

    /** Encodes a binary mask (white where {@code fg} is true) as a PNG. */
    private static byte[] binaryPng(int w, int h, java.util.function.BiPredicate<Integer, Integer> fg)
            throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, fg.test(x, y) ? 0xFFFFFF : 0);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static boolean whiteAt(byte[] png, int x, int y) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return (img.getRGB(x, y) & 0xff) > 127;
    }

    @Test
    void resizeBinarySmoothScalesToTargetAndKeepsShape() throws Exception {
        // Left half white, right half black, 20x10 → 40x20.
        byte[] mask = binaryPng(20, 10, (x, y) -> x < 10);
        byte[] resized = MaskProcessor.resizeBinarySmooth(mask, 40, 20);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(resized));
        assertThat(img.getWidth()).isEqualTo(40);
        assertThat(img.getHeight()).isEqualTo(20);
        // Interior of each half survives; the re-threshold keeps it binary.
        assertThat(whiteAt(resized, 8, 10)).isTrue();
        assertThat(whiteAt(resized, 32, 10)).isFalse();
    }

    @Test
    void resizeBinarySmoothIsIdentityAtSameSize() throws Exception {
        byte[] mask = binaryPng(20, 10, (x, y) -> x < 10);
        assertThat(MaskProcessor.resizeBinarySmooth(mask, 20, 10)).isSameAs(mask);
    }

    // ---------------------------------------------------------------------
    // restrictToPaintable
    // ---------------------------------------------------------------------

    private static final int GATE_SPREAD = 70;
    private static final int GATE_MIN_LUMA = 78;
    private static final double GATE_MAX_REMOVED = 0.5;

    /** Canvas of vertical bands (same geometry as {@link #strip}). */
    private static BufferedImage canvasStrip(Color... bands) {
        BufferedImage img = new BufferedImage(BAND * bands.length, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < bands.length; i++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < BAND; x++) {
                    img.setRGB(i * BAND + x, y, bands[i].getRGB());
                }
            }
        }
        return img;
    }

    @Test
    void restrictToPaintableDropsNonPaintPixels() throws Exception {
        // Cleaned canvas: mostly white wall (bright + dusk-warm + shadowed),
        // with a charcoal railing band and a sky band the mask bled onto.
        // Paint must stay the majority or the safety valve (rightly) refuses
        // to gate — real masks are mostly wall with bleed at the borders.
        BufferedImage canvas = canvasStrip(
                new Color(238, 234, 226),   // fresh white paint, full light → keep
                new Color(200, 170, 140),   // same paint in warm dusk light → keep
                new Color(120, 108, 96),    // same paint in deep shade → keep
                new Color(67, 70, 74),      // charcoal railing (luma ~70) → drop
                new Color(120, 160, 215));  // sky (spread ~95) → drop
        byte[] mask = binaryPng(canvas.getWidth(), canvas.getHeight(), (x, y) -> true);

        byte[] gated = MaskProcessor.restrictToPaintable(
                mask, canvas, GATE_SPREAD, GATE_MIN_LUMA, GATE_MAX_REMOVED);

        assertThat(bandIsForeground(gated, 0)).isTrue();
        assertThat(bandIsForeground(gated, 1)).isTrue();
        assertThat(bandIsForeground(gated, 2)).isTrue();
        assertThat(bandIsForeground(gated, 3)).isFalse();
        assertThat(bandIsForeground(gated, 4)).isFalse();
    }

    @Test
    void restrictToPaintableFallsBackWhenItWouldRemoveTooMuch() throws Exception {
        // Canvas is all charcoal — the gate would wipe 100% of the mask, which
        // means the canvas is not the white repaint. Input must come back as-is.
        BufferedImage canvas = canvasStrip(
                new Color(67, 70, 74), new Color(67, 70, 74), new Color(67, 70, 74));
        byte[] mask = binaryPng(canvas.getWidth(), canvas.getHeight(), (x, y) -> true);

        byte[] gated = MaskProcessor.restrictToPaintable(
                mask, canvas, GATE_SPREAD, GATE_MIN_LUMA, GATE_MAX_REMOVED);

        assertThat(gated).isSameAs(mask);
        assertThat(bandIsForeground(gated, 1)).isTrue();
    }
}
