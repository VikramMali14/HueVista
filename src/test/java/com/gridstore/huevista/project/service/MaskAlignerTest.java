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
                        fit.scaleX(), fit.scaleY(), fit.offsetX(), fit.offsetY());
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
}
