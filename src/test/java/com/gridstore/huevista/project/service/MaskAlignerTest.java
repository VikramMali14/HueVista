package com.gridstore.huevista.project.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MaskAligner} and the resampler it drives,
 * {@link MaskProcessor#resizeBinaryAligned}.
 *
 * <p>The assertions are about the OUTCOME, not the numbers: what matters is
 * that the stored mask ends up covering the wall the canvas actually has, so
 * every test measures the overlap (intersection over union) between the mask
 * the pipeline would store and the surface it is supposed to describe. A test
 * on the fit's raw scale/offset would pass or fail on the search's step size
 * instead.
 */
class MaskAlignerTest {

    private static final int CANVAS_W = 400;
    private static final int CANVAS_H = 300;

    /** Where the wall really is in every synthetic canvas below. */
    private static final int WALL_X0 = 100, WALL_X1 = 300, WALL_Y0 = 80, WALL_Y1 = 250;

    /**
     * A photo-like canvas: a bright wall on a dark background, with a window
     * punched out of it and a ground line — enough real edges that the
     * aligner's percentile normalization has something to normalize against,
     * which a two-tone test card would not give it.
     */
    private static BufferedImage canvas() {
        Random rnd = new Random(7);
        BufferedImage img = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                int v;
                boolean inWall = x >= WALL_X0 && x < WALL_X1 && y >= WALL_Y0 && y < WALL_Y1;
                boolean inWindow = x >= 150 && x < 210 && y >= 120 && y < 180;
                if (inWall && inWindow) v = 25;          // dark glass
                else if (inWall) v = 205;                // lit wall
                else if (y >= WALL_Y1) v = 90;           // ground
                else v = 45;                             // sky
                v = Math.max(0, Math.min(255, v + rnd.nextInt(13) - 6));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    /**
     * The model's answer at the canvas's own size: the wall flooded RED,
     * everything else black, drawn {@code dx},{@code dy} px away from where the
     * wall really is.
     */
    private static BufferedImage colorMask(int dx, int dy) {
        return colorMask(CANVAS_W, CANVAS_H, dx, dy, 1);
    }

    /**
     * The same answer re-rendered into a {@code w}×{@code h} frame — the model
     * rounding its output to one of its aspect buckets. {@code k} is the scale
     * it drew the scene at inside that frame, centred: the scene keeps ITS OWN
     * proportions and the frame crops or pads around it, which is why
     * stretching the result back onto the canvas shears every region.
     */
    private static BufferedImage colorMask(int w, int h, int dx, int dy, double k) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cx = (x - w / 2.0) / k + CANVAS_W / 2.0 - dx;
                double cy = (y - h / 2.0) / k + CANVAS_H / 2.0 - dy;
                boolean inWall = cx >= WALL_X0 && cx < WALL_X1 && cy >= WALL_Y0 && cy < WALL_Y1;
                boolean inWindow = cx >= 150 && cx < 210 && cy >= 120 && cy < 180;
                img.setRGB(x, y, inWall && !inWindow ? 0xFF0000 : 0x000000);
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * Overlap between a stored mask and the wall the canvas really has —
     * 1.0 is a perfect fit, and the window is excluded from the wall exactly
     * as the model drew it.
     */
    private static double iouWithRealWall(byte[] maskPng) throws Exception {
        BufferedImage mask = ImageIO.read(new ByteArrayInputStream(maskPng));
        int inter = 0, union = 0;
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                boolean truth = x >= WALL_X0 && x < WALL_X1 && y >= WALL_Y0 && y < WALL_Y1
                        && !(x >= 150 && x < 210 && y >= 120 && y < 180);
                boolean painted = (mask.getRGB(x, y) & 0xff) > 127;
                if (truth && painted) inter++;
                if (truth || painted) union++;
            }
        }
        return union == 0 ? 0 : (double) inter / union;
    }

    /** The "main" mask the pipeline would store for this generation and fit. */
    private static byte[] storedMask(BufferedImage colorMask, MaskAligner.Fit fit) throws Exception {
        byte[] main = MaskProcessor.splitColorCodedMask(png(colorMask), 100, false).get("main");
        assertThat(main).as("the red block must split out as the main wall").isNotNull();
        return fit.isIdentity()
                ? MaskProcessor.resizeBinarySmooth(main, CANVAS_W, CANVAS_H)
                : MaskProcessor.resizeBinaryAligned(main, CANVAS_W, CANVAS_H,
                        fit.scaleX(), fit.scaleY(), fit.offsetX(), fit.offsetY(), fit.warp());
    }

    /**
     * The model's answer with a drift that VARIES across the frame instead of
     * being the same everywhere: the scene is pulled {@code ampX} px sideways
     * at the bottom and the same the other way at the top, and {@code ampY} px
     * up one side and down the other. No scale-and-translate can express that,
     * which is the point — it is what a generative repaint of a facade
     * actually does, and it is why a single rigid fit leaves paint over the
     * sky along one roofline while the windows below it were already right.
     */
    private static BufferedImage warpedColorMask(double ampX, double ampY) {
        BufferedImage img = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                double cx = x - ampX * ((double) y / CANVAS_H - 0.5) * 2;
                double cy = y - ampY * ((double) x / CANVAS_W - 0.5) * 2;
                boolean inWall = cx >= WALL_X0 && cx < WALL_X1 && cy >= WALL_Y0 && cy < WALL_Y1;
                boolean inWindow = cx >= 150 && cx < 210 && cy >= 120 && cy < 180;
                img.setRGB(x, y, inWall && !inWindow ? 0xFF0000 : 0x000000);
            }
        }
        return img;
    }

    @Test
    void putsAShiftedMaskBackOnTheWall() throws Exception {
        BufferedImage canvas = canvas();
        BufferedImage drifted = colorMask(12, -8);

        double before = iouWithRealWall(storedMask(drifted, MaskAligner.Fit.identity()));
        MaskAligner.Fit fit = MaskAligner.estimate(drifted, canvas);

        assertThat(fit.isIdentity()).as("a 12px drift is exactly what this is for").isFalse();
        double after = iouWithRealWall(storedMask(drifted, fit));
        assertThat(after).isGreaterThan(before);
        assertThat(after).isGreaterThan(0.95);
    }

    @Test
    void unshearsAnAspectBucketedMask() throws Exception {
        BufferedImage canvas = canvas();                       // 4:3
        // The model rounded its output to 3:2 and re-rendered the scene into
        // it at its own proportions, cropping the extra height. Stretching
        // that back onto the canvas is what shears every region.
        BufferedImage bucketed = colorMask(480, 320, 0, 0, 480.0 / CANVAS_W);

        double before = iouWithRealWall(storedMask(bucketed, MaskAligner.Fit.identity()));
        MaskAligner.Fit fit = MaskAligner.estimate(bucketed, canvas);

        assertThat(fit.isIdentity()).as("an 11% shear is not something to keep").isFalse();
        double after = iouWithRealWall(storedMask(bucketed, fit));
        assertThat(after).isGreaterThan(before);
        assertThat(after).isGreaterThan(0.90);
    }

    @Test
    void leavesAnAlreadyAlignedMaskExactlyAsDrawn() throws Exception {
        MaskAligner.Fit fit = MaskAligner.estimate(colorMask(0, 0), canvas());
        assertThat(fit.isIdentity()).isTrue();
    }

    @Test
    void refusesToMoveAMaskAgainstACanvasWithNoEdges() {
        BufferedImage blank = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) blank.setRGB(x, y, 0x808080);
        }
        // Nothing to align to: the correction would be a guess, so there isn't one.
        assertThat(MaskAligner.estimate(colorMask(12, -8), blank).isIdentity())
                .isTrue();
    }

    @Test
    void neverMovesARegionFurtherThanTheCap() {
        // Way past what the search may reach: the fit must stay inside its
        // limits rather than chase the wall off the frame.
        MaskAligner.Fit fit = MaskAligner.estimate(colorMask(140, 90), canvas());
        assertThat(Math.abs(fit.offsetX())).isLessThanOrEqualTo(0.05);
        assertThat(Math.abs(fit.offsetY())).isLessThanOrEqualTo(0.05);
        // Mask and canvas share an aspect here, so every anchor is 1:1 and the
        // only size freedom left is the jitter the search is allowed around it.
        assertThat(fit.scaleX()).isBetween(0.94, 1.06);
        assertThat(fit.scaleY()).isBetween(0.94, 1.06);
    }

    @Test
    void alignedResampleKeepsTheRegionsShapeAndJustMovesIt() throws Exception {
        // A plain white block, moved a quarter of the frame to the right: the
        // resampler must translate it, not grow, shrink or smear it.
        BufferedImage block = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 50; y < 150; y++) {
            for (int x = 20; x < 60; x++) block.setRGB(x, y, 0xFFFFFF);
        }
        byte[] moved = MaskProcessor.resizeBinaryAligned(png(block), 200, 200, 1, 1, 0.25, 0);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(moved));

        int minX = 200, maxX = -1, count = 0;
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                if ((out.getRGB(x, y) & 0xff) > 127) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    count++;
                }
            }
        }
        assertThat(minX).isCloseTo(70, org.assertj.core.data.Offset.offset(1));
        assertThat(maxX).isCloseTo(109, org.assertj.core.data.Offset.offset(1));
        assertThat(count).isCloseTo(40 * 100, org.assertj.core.data.Offset.offset(200));
    }

    @Test
    void identityResampleUpscalesAboutTheTexelCentre() throws Exception {
        // A block covering the middle half of a 120px row, doubled: it must
        // still cover the middle half — 60..180 of 240 — rather than slide
        // half an output pixel per doubling.
        BufferedImage block = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
        for (int y = 20; y < 70; y++) {
            for (int x = 30; x < 90; x++) block.setRGB(x, y, 0xFFFFFF);
        }
        byte[] resized = MaskProcessor.resizeBinarySmooth(png(block), 240, 180);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(resized));

        int minX = 240, maxX = -1, minY = 180, maxY = -1;
        for (int y = 0; y < 180; y++) {
            for (int x = 0; x < 240; x++) {
                if ((out.getRGB(x, y) & 0xff) > 127) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertThat(minX).isEqualTo(60);
        assertThat(maxX).isEqualTo(179);
        assertThat(minY).isEqualTo(40);
        assertThat(maxY).isEqualTo(139);
    }

    @Test
    void followsADriftThatChangesAcrossTheFrame() throws Exception {
        BufferedImage canvas = canvas();
        // 10px sideways at the top and bottom, 9px up and down the sides: a
        // couple of percent of the frame, and in a different direction in
        // every corner.
        BufferedImage warped = warpedColorMask(10, 9);

        double before = iouWithRealWall(storedMask(warped, MaskAligner.Fit.identity()));
        MaskAligner.Fit fit = MaskAligner.estimate(warped, canvas);

        assertThat(fit.warp())
                .as("a drift with no single rigid answer is what the local field is for")
                .isNotNull();
        double after = iouWithRealWall(storedMask(warped, fit));
        assertThat(after).isGreaterThan(before);
        assertThat(after).isGreaterThan(0.94);
    }

    @Test
    void doesNotPullOneWallOffToPutAnotherOn() throws Exception {
        // Two surfaces the model drifted opposite ways. The frame-wide fit that
        // scores best here lands ONE of them perfectly and shoves the other
        // further off — a big win on the average it is judged by, and a worse
        // mask. Whatever the aligner returns, the stored mask has to cover more
        // of the two walls than leaving it alone would.
        BufferedImage canvas = twoWallCanvas();
        BufferedImage drifted = twoWallColorMask(10, -7, -9, 8);

        double before = iouWithBothWalls(twoWallStoredMask(drifted, MaskAligner.Fit.identity()));
        MaskAligner.Fit fit = MaskAligner.estimate(drifted, canvas);
        double after = iouWithBothWalls(twoWallStoredMask(drifted, fit));

        assertThat(after).isGreaterThan(before);
        assertThat(after).isGreaterThan(0.83);
    }

    @Test
    void neverMovesAnyPartOfTheFrameFurtherThanTheLocalCap() {
        // Far past what the local search may reach, on a canvas full of edges
        // it could chase: no node of the field may exceed its cap.
        MaskAligner.Fit fit = MaskAligner.estimate(twoWallColorMask(60, 40, -55, -45), twoWallCanvas());
        if (fit.warp() != null) {
            assertThat(fit.warp().maxShift()).isLessThanOrEqualTo(0.0300001);
        }
    }

    @Test
    void warpedResampleMovesEachPartOfTheMaskByItsOwnAmount() throws Exception {
        // Two white blocks, one at the top of the frame and one at the bottom,
        // under a field that pushes the top one right and the bottom one left.
        // The resampler must move each by its own amount, not both by an
        // average of the two.
        BufferedImage blocks = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 20; y < 60; y++) {
            for (int x = 80; x < 120; x++) blocks.setRGB(x, y, 0xFFFFFF);
        }
        for (int y = 140; y < 180; y++) {
            for (int x = 80; x < 120; x++) blocks.setRGB(x, y, 0xFFFFFF);
        }
        // One column of cells, two rows, so the lattice's three rows of nodes sit
        // at the top, middle and bottom of the frame: +0.1 of the frame at the
        // top, 0 in the middle, -0.1 at the bottom, interpolated in between.
        MaskAligner.Warp warp = new MaskAligner.Warp(1, 2,
                new double[]{0.1, 0.1, 0.0, 0.0, -0.1, -0.1},
                new double[]{0, 0, 0, 0, 0, 0});

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(
                MaskProcessor.resizeBinaryAligned(png(blocks), 200, 200, 1, 1, 0, 0, warp)));

        // Each block sits at the middle of its half of the frame, where the
        // field reads ±0.06 — 12px of this 200px frame — so they end up 24px
        // apart having started on top of each other.
        assertThat(centreX(out, 20, 60)).isCloseTo(112d, org.assertj.core.data.Offset.offset(2d));
        assertThat(centreX(out, 140, 180)).isCloseTo(88d, org.assertj.core.data.Offset.offset(2d));
    }

    /** Mean x of the foreground pixels in rows {@code y0..y1}. */
    private static double centreX(BufferedImage img, int y0, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xff) > 127) {
                    sum += x;
                    n++;
                }
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    // ---- two-surface fixture ------------------------------------------------
    //
    // The single-wall canvas above cannot show the failure this exists for: one
    // wall has one right answer, and a frame-wide fit can always reach it. Two
    // surfaces that drifted different ways cannot both be fixed by one.

    private static final int L_X0 = 40, L_X1 = 180, L_Y0 = 60, L_Y1 = 240;
    private static final int LW_X0 = 80, LW_X1 = 120, LW_Y0 = 100, LW_Y1 = 150;
    private static final int R_X0 = 220, R_X1 = 370, R_Y0 = 90, R_Y1 = 250;
    private static final int RW_X0 = 270, RW_X1 = 320, RW_Y0 = 130, RW_Y1 = 180;

    private static boolean inLeftWall(double x, double y) {
        return x >= L_X0 && x < L_X1 && y >= L_Y0 && y < L_Y1
                && !(x >= LW_X0 && x < LW_X1 && y >= LW_Y0 && y < LW_Y1);
    }

    private static boolean inRightWall(double x, double y) {
        return x >= R_X0 && x < R_X1 && y >= R_Y0 && y < R_Y1
                && !(x >= RW_X0 && x < RW_X1 && y >= RW_Y0 && y < RW_Y1);
    }

    private static BufferedImage twoWallCanvas() {
        Random rnd = new Random(11);
        BufferedImage img = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                boolean glass = (x >= LW_X0 && x < LW_X1 && y >= LW_Y0 && y < LW_Y1)
                        || (x >= RW_X0 && x < RW_X1 && y >= RW_Y0 && y < RW_Y1);
                int v;
                if (glass) v = 25;
                else if (inLeftWall(x, y) || inRightWall(x, y)) v = 205;
                else if (y >= 250) v = 90;
                else v = 45;
                v = Math.max(0, Math.min(255, v + rnd.nextInt(13) - 6));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static BufferedImage twoWallColorMask(int ldx, int ldy, int rdx, int rdy) {
        BufferedImage img = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                boolean red = inLeftWall(x - ldx, y - ldy) || inRightWall(x - rdx, y - rdy);
                img.setRGB(x, y, red ? 0xFF0000 : 0x000000);
            }
        }
        return img;
    }

    private static byte[] twoWallStoredMask(BufferedImage colorMask, MaskAligner.Fit fit)
            throws Exception {
        byte[] main = MaskProcessor.splitColorCodedMask(png(colorMask), 100, false).get("main");
        assertThat(main).isNotNull();
        return fit.isIdentity()
                ? MaskProcessor.resizeBinarySmooth(main, CANVAS_W, CANVAS_H)
                : MaskProcessor.resizeBinaryAligned(main, CANVAS_W, CANVAS_H,
                        fit.scaleX(), fit.scaleY(), fit.offsetX(), fit.offsetY(), fit.warp());
    }

    private static double iouWithBothWalls(byte[] maskPng) throws Exception {
        BufferedImage mask = ImageIO.read(new ByteArrayInputStream(maskPng));
        int inter = 0, union = 0;
        for (int y = 0; y < CANVAS_H; y++) {
            for (int x = 0; x < CANVAS_W; x++) {
                boolean truth = inLeftWall(x, y) || inRightWall(x, y);
                boolean painted = (mask.getRGB(x, y) & 0xff) > 127;
                if (truth && painted) inter++;
                if (truth || painted) union++;
            }
        }
        return union == 0 ? 0 : (double) inter / union;
    }
}
