package com.gridstore.huevista.project.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MaskRefiner} — the server-side edge snap that
 * re-attaches a mask boundary to the canvas's real colour edges. Mirrors the
 * frontend's mask-refine tests: same algorithm, same guarantees.
 */
class MaskRefinerTest {

    private static final int W = 80;
    private static final int H = 40;

    /** A wall/sky canvas: warm beige left of {@code edgeX}, blue right of it. */
    private static float[][] wallSkyGuide(int edgeX) {
        float[] r = new float[W * H];
        float[] g = new float[W * H];
        float[] b = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                boolean wall = x < edgeX;
                r[i] = wall ? 0.85f : 0.55f;
                g[i] = wall ? 0.80f : 0.65f;
                b[i] = wall ? 0.70f : 0.90f;
            }
        }
        return new float[][]{r, g, b};
    }

    private static float[] verticalMask(int boundaryX) {
        float[] m = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) m[y * W + x] = x < boundaryX ? 1f : 0f;
        }
        return m;
    }

    private static float[] refine(float[][] guide, float[] mask) {
        float[] q = MaskRefiner.guidedFilter(guide[0], guide[1], guide[2], mask,
                W, H, MaskRefiner.SNAP_RADIUS, MaskRefiner.SNAP_EPS);
        return MaskRefiner.snap(mask, q, W, H, MaskRefiner.SNAP_BAND_PX);
    }

    /** x of the first pixel below 0.5 scanning row {@code y} left → right. */
    private static int crossing(float[] alpha, int y) {
        for (int x = 0; x < W; x++) {
            if (alpha[y * W + x] < 0.5f) return x;
        }
        return W;
    }

    @Test
    void movesOvershootingBoundaryOntoTheRealEdge() {
        // Canvas edge at x=40; the mask overshoots to x=43 (onto the "sky").
        float[] alpha = refine(wallSkyGuide(40), verticalMask(43));
        for (int y = 8; y < H - 8; y += 4) {
            assertThat(Math.abs(crossing(alpha, y) - 40)).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void pullsUndershootingBoundaryOutToTheRealEdge() {
        // The mask stops 3px short of the wall/sky line (unpainted sliver).
        float[] alpha = refine(wallSkyGuide(40), verticalMask(37));
        for (int y = 8; y < H - 8; y += 4) {
            assertThat(Math.abs(crossing(alpha, y) - 40)).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void neverMovesAnEdgeBeyondTheSnapBand() {
        // Canvas edge 12px away from the mask boundary at x=52 — far outside
        // the band. Snapping must not migrate the region onto the sky.
        float[] alpha = refine(wallSkyGuide(40), verticalMask(52));
        int y = 20;
        assertThat(alpha[y * W + 52 + MaskRefiner.SNAP_BAND_PX + 1]).isZero();
        assertThat(alpha[y * W + 52 - MaskRefiner.SNAP_BAND_PX - 2]).isEqualTo(1f);
    }

    @Test
    void keepsATightBoundaryOnAFlatCanvas() {
        // Uniform canvas (no edge anywhere): the boundary must stay within the
        // band around the mask's own line instead of smearing over the radius.
        float[] flatR = new float[W * H];
        float[] flatG = new float[W * H];
        float[] flatB = new float[W * H];
        java.util.Arrays.fill(flatR, 0.8f);
        java.util.Arrays.fill(flatG, 0.78f);
        java.util.Arrays.fill(flatB, 0.74f);
        float[] alpha = refine(new float[][]{flatR, flatG, flatB}, verticalMask(40));
        int y = 20;
        assertThat(alpha[y * W + 40 - MaskRefiner.SNAP_BAND_PX - 2]).isEqualTo(1f);
        assertThat(alpha[y * W + 40 + MaskRefiner.SNAP_BAND_PX + 1]).isZero();
        assertThat(Math.abs(crossing(alpha, y) - 40)).isLessThanOrEqualTo(2);
    }

    @Test
    void snapToCanvasReturnsBinaryMaskAtTheInputResolution() throws Exception {
        // End-to-end through the PNG path: canvas 160x80 with an edge at
        // x=80, mask 160x80 with its boundary misregistered to x=83 (a 3px
        // overshoot — within the snap band, so it must be pulled back).
        int w = 160, h = 80;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                canvas.setRGB(x, y, x < 80 ? 0xD9CCB2 : 0x8CA6E6);
            }
        }
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < 83; x++) mask.setRGB(x, y, 0xFFFFFF);
        }
        ByteArrayOutputStream maskPng = new ByteArrayOutputStream();
        ImageIO.write(mask, "png", maskPng);

        byte[] refined = MaskRefiner.snapToCanvas(maskPng.toByteArray(), canvas);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(refined));
        assertThat(out.getWidth()).isEqualTo(w);
        assertThat(out.getHeight()).isEqualTo(h);
        int y = h / 2;
        // Binary contract and snapped boundary: white well inside the wall,
        // black on the sky side of the real edge.
        assertThat(out.getRGB(40, y) & 0xff).isGreaterThan(127);
        assertThat(out.getRGB(82, y) & 0xff).isLessThan(128); // old overshoot now sky
        assertThat(out.getRGB(77, y) & 0xff).isGreaterThan(127); // still wall
    }
}
