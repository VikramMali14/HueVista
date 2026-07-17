package com.gridstore.huevista.project.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MaskStraightener} — the polygonal boundary
 * regularisation that turns the mask model's wobbly hand-painted lines into
 * ruler-straight segments while keeping genuine corners and holes.
 */
class MaskStraightenerTest {

    private static final int W = 400;
    private static final int H = 260;

    /** Rectangle mask [x0,x1)×[y0,y1) with a wobbly top edge: the boundary
     *  oscillates ±amp px around y0, like the mask model's hand wobble. */
    private static boolean[] wobblyTopRect(int x0, int x1, int y0, int y1, double amp) {
        boolean[] bin = new boolean[W * H];
        for (int x = x0; x < x1; x++) {
            int top = y0 + (int) Math.round(amp * Math.sin((x - x0) * 0.35));
            for (int y = top; y < y1; y++) bin[y * W + x] = true;
        }
        return bin;
    }

    /** Top boundary y for column {@code x} (first foreground row), or -1. */
    private static int topY(boolean[] bin, int x) {
        for (int y = 0; y < H; y++) {
            if (bin[y * W + x]) return y;
        }
        return -1;
    }

    /** Max residual of the samples against their least-squares line — 0 for
     *  perfectly straight, large for a wobbly boundary. */
    private static double maxLineFitResidual(double[] xs, double[] ys) {
        int n = xs.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sx += xs[i];
            sy += ys[i];
            sxx += xs[i] * xs[i];
            sxy += xs[i] * ys[i];
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double intercept = (sy - slope * sx) / n;
        double worst = 0;
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, Math.abs(ys[i] - (slope * xs[i] + intercept)));
        }
        return worst;
    }

    @Test
    void straightensAWobblyEdgeIntoALine() {
        // Tolerance must exceed ~2× the wobble amplitude (a chord anchored at
        // one wobble peak sees the opposite peak at 2× amplitude) — the same
        // ratio the production epsilon keeps at stored-mask resolution.
        boolean[] bin = wobblyTopRect(40, 360, 60, 220, 4.0);
        boolean[] out = MaskStraightener.straighten(bin, W, H, 9.0);

        // Sample the top boundary away from the corners and fit a line:
        // the wobble (±4px) must collapse to a near-perfect straight edge.
        int samples = 0;
        double[] xs = new double[300];
        double[] ys = new double[300];
        for (int x = 60; x < 340; x++) {
            int y = topY(out, x);
            assertThat(y).isNotNegative();
            xs[samples] = x;
            ys[samples] = y;
            samples++;
        }
        double[] fx = java.util.Arrays.copyOf(xs, samples);
        double[] fy = java.util.Arrays.copyOf(ys, samples);
        assertThat(maxLineFitResidual(fx, fy)).isLessThanOrEqualTo(1.5);

        // ... while the input edge really was wobbly.
        samples = 0;
        for (int x = 60; x < 340; x++) {
            fx[samples] = x;
            fy[samples] = topY(bin, x);
            samples++;
        }
        assertThat(maxLineFitResidual(fx, fy)).isGreaterThan(3.0);
    }

    @Test
    void keepsGenuineCornersAndHoles() {
        // Clean rectangle with a rectangular window hole; straightening a
        // mask that is already polygonal must reproduce it exactly.
        boolean[] bin = new boolean[W * H];
        for (int y = 40; y < 220; y++) {
            for (int x = 50; x < 350; x++) bin[y * W + x] = true;
        }
        for (int y = 100; y < 160; y++) {
            for (int x = 150; x < 250; x++) bin[y * W + x] = false;
        }

        boolean[] out = MaskStraightener.straighten(bin, W, H, 6.0);
        assertThat(out).isEqualTo(bin);
    }

    @Test
    void thinFeaturesSurviveTheLoopEpsilonCap() {
        // Wall with a 5px-tall railing gap running across it. The global
        // tolerance (8px) exceeds the gap's thickness, but the per-loop cap
        // must keep the gap open instead of collapsing it into the wall.
        boolean[] bin = new boolean[W * H];
        for (int y = 40; y < 220; y++) {
            for (int x = 50; x < 350; x++) bin[y * W + x] = true;
        }
        for (int y = 120; y < 125; y++) {
            for (int x = 60; x < 340; x++) bin[y * W + x] = false;
        }

        boolean[] out = MaskStraightener.straighten(bin, W, H, 8.0);
        for (int x = 80; x < 320; x += 20) {
            assertThat(out[122 * W + x]).as("railing gap at x=%d stays open", x).isFalse();
        }
        assertThat(out[80 * W + 200]).isTrue();
        assertThat(out[180 * W + 200]).isTrue();
    }

    @Test
    void blankAndFullMasksPassThroughUnchanged() {
        boolean[] blank = new boolean[W * H];
        assertThat(MaskStraightener.straighten(blank, W, H, 6.0)).isEqualTo(blank);

        boolean[] full = new boolean[W * H];
        java.util.Arrays.fill(full, true);
        assertThat(MaskStraightener.straighten(full, W, H, 6.0)).isEqualTo(full);
    }

    @Test
    void changeBudgetRejectsWholesaleRewrites() {
        // Boundary movement within epsilon of the perimeter is accepted...
        boolean[] before = wobblyTopRect(40, 360, 60, 220, 4.0);
        boolean[] straightened = MaskStraightener.straighten(before, W, H, 6.0);
        assertThat(MaskStraightener.changedWithinBudget(before, straightened, W, H, 6.0)).isTrue();

        // ...but losing the mask entirely is not explainable by wobble and
        // must be rejected (the caller then keeps the input bytes).
        boolean[] wipedOut = new boolean[W * H];
        assertThat(MaskStraightener.changedWithinBudget(before, wipedOut, W, H, 6.0)).isFalse();
    }

    @Test
    void straightenPngKeepsResolutionAndBinaryContract() throws Exception {
        boolean[] bin = wobblyTopRect(40, 360, 60, 220, 4.0);
        BufferedImage mask = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (bin[y * W + x]) mask.setRGB(x, y, 0xFFFFFF);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(mask, "png", png);

        byte[] straightened = MaskStraightener.straighten(png.toByteArray());

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(straightened));
        assertThat(out.getWidth()).isEqualTo(W);
        assertThat(out.getHeight()).isEqualTo(H);
        for (int y = 0; y < H; y += 7) {
            for (int x = 0; x < W; x += 7) {
                int g = out.getRGB(x, y) & 0xff;
                assertThat(g == 0 || g == 255).as("binary at %d,%d", x, y).isTrue();
            }
        }
        // The interior is intact and the sky stays clear.
        assertThat(out.getRGB(200, 150) & 0xff).isEqualTo(255);
        assertThat(out.getRGB(200, 20) & 0xff).isZero();
    }
}
