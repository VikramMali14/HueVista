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
    /** Black rows above the bands: the white-salvage sky filter rejects blobs
     *  touching the top of the frame, so band content starts below it. */
    private static final int TOP_MARGIN = 2;
    private static final int MIN_PIXELS = 20;    // each band has BAND*(HEIGHT-TOP_MARGIN) = 60 px

    /** Builds a horizontal strip of solid-colour bands (below a black top
     *  margin), one per supplied colour. */
    private static byte[] strip(Color... bands) throws Exception {
        BufferedImage img = new BufferedImage(BAND * bands.length, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < bands.length; i++) {
            for (int y = TOP_MARGIN; y < HEIGHT; y++) {
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

    @Test
    void salvagesWhiteAsAccentWhenGreenIsMissing() throws Exception {
        // The model disobeyed the palette and left the feature wall WHITE
        // (no green anywhere). The white area must become the accent mask
        // instead of being dropped — the user expects three regions.
        byte[] coded = strip(Color.RED, Color.WHITE, Color.BLUE, Color.BLACK);

        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);

        assertThat(parts).containsOnlyKeys("main", "accent", "trim");
        assertThat(bandIsForeground(parts.get("accent"), 1)).isTrue();   // white band
        assertThat(bandIsForeground(parts.get("accent"), 0)).isFalse();  // not the red band
        assertThat(bandIsForeground(parts.get("main"), 1)).isFalse();    // white isn't main
    }

    @Test
    void salvagesNearWhiteOffSpecPixels() throws Exception {
        // Slightly warm off-white (JPEG drift / model shading) still counts.
        byte[] coded = strip(Color.RED, new Color(238, 230, 214), Color.BLACK);
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);
        assertThat(parts).containsKey("accent");
        assertThat(bandIsForeground(parts.get("accent"), 1)).isTrue();
    }

    // ---------------------------------------------------------------------
    // white-salvage filtering (largest blob only, sky rejected)
    // ---------------------------------------------------------------------

    /** Blank (black) canvas the salvage tests paint rectangles onto. */
    private static BufferedImage blank(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
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
    void whiteSalvageRejectsSkyAndAdoptsTheWallBlob() throws Exception {
        // Off-spec output: the model left BOTH the overcast sky and the accent
        // volume white. The sky strip touches the top of the frame (and is the
        // larger blob); the wall blob sits lower. Salvage must adopt the wall,
        // never the sky — otherwise the user gets a paintable "accent wall"
        // that recolours the sky.
        BufferedImage img = blank(100, 50);
        fill(img, Color.WHITE, 0, 0, 100, 10);    // sky: full width, touches top
        fill(img, Color.RED, 0, 20, 50, 30);      // main wall
        fill(img, Color.WHITE, 60, 25, 30, 20);   // accent volume left white
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(png(img), MIN_PIXELS);

        assertThat(parts).containsKey("accent");
        assertThat(whiteAt(parts.get("accent"), 75, 35)).isTrue();   // wall blob
        assertThat(whiteAt(parts.get("accent"), 50, 5)).isFalse();   // sky excluded
    }

    @Test
    void whiteSalvageOmitsAccentWhenSkyIsTheOnlyCandidate() throws Exception {
        BufferedImage img = blank(100, 50);
        fill(img, Color.WHITE, 0, 0, 100, 10);    // only white blob = sky
        fill(img, Color.RED, 0, 20, 100, 30);
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(png(img), MIN_PIXELS);

        assertThat(parts).containsOnlyKeys("main");
    }

    @Test
    void whiteSalvageKeepsOnlyTheLargestBlob() throws Exception {
        // An accent wall is ONE surface: scattered white noise (clouds, a
        // bright car, reflections) must not be unioned into the salvage.
        BufferedImage img = blank(100, 50);
        fill(img, Color.RED, 0, 20, 40, 30);
        fill(img, Color.WHITE, 50, 20, 30, 25);   // the wall (750 px)
        fill(img, Color.WHITE, 88, 40, 6, 6);     // noise blob (36 px ≥ MIN_PIXELS)
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(png(img), MIN_PIXELS);

        assertThat(parts).containsKey("accent");
        assertThat(whiteAt(parts.get("accent"), 65, 30)).isTrue();   // wall kept
        assertThat(whiteAt(parts.get("accent"), 91, 43)).isFalse();  // noise dropped
    }

    @Test
    void interiorWhiteSalvageKeepsAWallTouchingTheTop() throws Exception {
        // Indoors there is no sky, and a photo cropped above the ceiling can
        // legitimately show a wall reaching the top edge — the sky filter is
        // off (skyFilter=false) so that wall is still salvaged.
        BufferedImage img = blank(100, 50);
        fill(img, Color.WHITE, 10, 0, 30, 30);    // wall touching the top
        fill(img, Color.RED, 50, 10, 50, 40);
        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(png(img), MIN_PIXELS, false);

        assertThat(parts).containsKey("accent");
        assertThat(whiteAt(parts.get("accent"), 25, 15)).isTrue();
    }

    @Test
    void prefersGreenAccentOverWhite() throws Exception {
        // Both a proper green accent AND a white patch exist: green is the
        // accent; the off-spec white stays unassigned (never unioned in).
        byte[] coded = strip(Color.RED, Color.GREEN, Color.WHITE, Color.BLACK);

        Map<String, byte[]> parts = MaskProcessor.splitColorCodedMask(coded, MIN_PIXELS);

        assertThat(bandIsForeground(parts.get("accent"), 1)).isTrue();   // green band
        assertThat(bandIsForeground(parts.get("accent"), 2)).isFalse();  // white ignored
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
