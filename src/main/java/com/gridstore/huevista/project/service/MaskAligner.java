package com.gridstore.huevista.project.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Puts a generative colour-coded mask back on the canvas it was drawn from.
 *
 * <p>The mask is not extracted from the photo — a generative image model
 * repaints the photo into flat colour blocks, so what comes back is a NEW
 * image that merely looks like the input. Two things routinely go wrong with
 * that, and both land as "the mask is off the wall":
 *
 * <ul>
 *   <li><b>Aspect bucketing.</b> Gemini-family models generate into fixed
 *       aspect buckets. {@code aspect_ratio=match_input_image} asks for the
 *       photo's aspect, and the answer is the NEAREST bucket to it — a 1.31
 *       photo comes back at 4:3 (1.33). The pipeline then stretched that image
 *       onto the canvas non-uniformly, which shears every region sideways by a
 *       few percent of the frame: dozens of pixels at 2K, most visible as
 *       paint running past a wall's corner onto the sky.</li>
 *   <li><b>Drift.</b> Even at the right aspect the repaint sits a little off
 *       the original — a wall edge lands a handful of pixels from where it is
 *       in the photo.</li>
 * </ul>
 *
 * <p>Both are rigid, whole-frame errors, so one rigid correction fixes them:
 * find the scale and translation that best drop the mask's colour-block
 * BOUNDARIES onto the canvas's own EDGES. A wall's outline in the mask should
 * sit on the wall's outline in the photo; when it doesn't, the offset that
 * makes it fit is the offset the model introduced.
 *
 * <p>The search is deliberately small and heavily guarded. It only ever
 * produces a scale/translate — it cannot warp a region into a shape the model
 * didn't draw — the correction is capped at {@link #MAX_OFFSET} of the frame
 * and {@link #MAX_SCALE_JITTER} of the size, and it is discarded unless it
 * beats leaving the mask alone by {@link #MIN_GAIN}. A photo with nothing to
 * align to (fog, a blank wall filling the frame) scores below
 * {@link #MIN_BASE_SCORE} and is left exactly as it was. The failure mode is
 * therefore "no change", which is the behaviour this replaces.
 */
final class MaskAligner {

    private MaskAligner() {}

    /** Longest side of the working grid the fit is searched on. Coarse on
     *  purpose: a 2K search would cost 60× more and find the same few pixels,
     *  because the errors being corrected are whole-frame, not local. */
    private static final int GRID = 256;

    /** Longest side of the grid the mask's boundaries are traced on. Finer than
     *  the score grid so thin trim blocks still contribute a boundary. */
    private static final int MASK_GRID = 384;

    /** At most this many boundary samples feed one score. */
    private static final int MAX_POINTS = 3000;

    /** Hard cap on the correction, as a share of the frame. */
    private static final double MAX_OFFSET = 0.05;

    /** Hard cap on the size correction around an anchor, as a fraction, and
     *  the step it is searched in. */
    private static final double MAX_SCALE_JITTER = 0.06;
    private static final double SCALE_STEP = 0.01;

    /** The widest aspect disagreement between mask and canvas still treated as
     *  a bucketing artefact. Past this the model did not round the frame, it
     *  answered about a different picture, and nothing here can rescue that. */
    private static final double MAX_ASPECT_DRIFT = 1.4;

    /** Leaving the mask alone has to be beaten by this factor before a fit is
     *  applied — a marginal win is noise, and moving a mask for noise is worse
     *  than the drift it claims to fix. */
    private static final double MIN_GAIN = 1.08;

    /** Below this, the canvas has no edges worth aligning to and every
     *  candidate scores the same near-nothing; keep the mask as drawn. */
    private static final double MIN_BASE_SCORE = 0.02;

    /** Gradient magnitudes at or above this percentile of the canvas count as
     *  a full-strength edge; everything scales against it. Robust to one
     *  blown-out highlight setting the scale for the whole frame. */
    private static final double EDGE_PERCENTILE = 0.95;

    /**
     * The correction to apply when resampling a mask onto the canvas, on top
     * of the plain stretch-to-fill that {@link Fit#identity()} represents.
     *
     * <p>Forward (mask → canvas, both in 0..1 of their own frame):
     * {@code u0 = 0.5 + (u - 0.5) * scaleX + offsetX}. The resampler uses the
     * inverse of that — see
     * {@link MaskProcessor#resizeBinaryAligned}.
     *
     * @param score     the winning fit's mean canvas-edge strength under the mask's boundary
     * @param baseScore the same measure for the untouched mask, for the logs
     */
    record Fit(double scaleX, double scaleY, double offsetX, double offsetY,
               double score, double baseScore) {

        static Fit identity() {
            return new Fit(1, 1, 0, 0, 0, 0);
        }

        boolean isIdentity() {
            return scaleX == 1 && scaleY == 1 && offsetX == 0 && offsetY == 0;
        }

        /** How far this fit moves the frame's centre, as a share of the frame. */
        double shift() {
            return Math.hypot(offsetX, offsetY);
        }

        @Override
        public String toString() {
            return String.format("scale %.3f×%.3f, offset %+.3f,%+.3f (edge score %.3f vs %.3f)",
                    scaleX, scaleY, offsetX, offsetY, score, baseScore);
        }
    }

    /**
     * Measures how the model's colour-coded mask sits on the canvas and returns
     * the correction that lines it up, or {@link Fit#identity()} when the mask
     * is already as good as anything the search can reach.
     *
     * @param colorMask the model's colour-coded image, at whatever size it came back
     * @param canvas    the image the frontend renders and the masks are stored against
     */
    static Fit estimate(BufferedImage colorMask, BufferedImage canvas) {
        if (colorMask == null || canvas == null) return Fit.identity();

        int cw = canvas.getWidth(), ch = canvas.getHeight();
        int mw = colorMask.getWidth(), mh = colorMask.getHeight();
        if (cw < 2 || ch < 2 || mw < 2 || mh < 2) return Fit.identity();

        double canvasAr = (double) cw / ch;
        int gw, gh;
        if (canvasAr >= 1) {
            gw = Math.min(GRID, cw);
            gh = Math.max(1, (int) Math.round(gw / canvasAr));
        } else {
            gh = Math.min(GRID, ch);
            gw = Math.max(1, (int) Math.round(gh * canvasAr));
        }
        // Too small a grid can't hold a meaningful edge map, and the search
        // step would be coarser than the error it is looking for.
        if (gw < 48 || gh < 48) return Fit.identity();

        float[] edges = edgeMap(canvas, gw, gh);
        float[] points = boundaryPoints(colorMask);
        // A mask with almost no boundary is a flood fill or a dud; there is
        // nothing to register and the score would ride on a handful of pixels.
        if (points.length < 400) return Fit.identity();

        double base = score(points, edges, gw, gh, 1, 1, 0, 0);
        if (base < MIN_BASE_SCORE) return Fit.identity();

        // Anchors: the ways the mask's frame can sensibly sit on the canvas's.
        // Plain stretch, {1,1}, is what the pipeline did before. The other two
        // are the only placements that keep the mask's own geometry
        // undistorted when the model bucketed the aspect — one fitting the
        // mask inside the canvas, one covering it — and they are what actually
        // undoes a shear. They coincide with the stretch when the aspects
        // already agree; the duplicates cost a pass and change nothing.
        //
        // These are NOT capped by MAX_SCALE_JITTER: a 3:2 answer to a 4:3
        // photo needs an 11% correction on one axis, and refusing to make it
        // is refusing to fix the very thing this exists for. The cap applies
        // to the search AROUND an anchor, where a large move would be a guess
        // rather than a geometric consequence.
        double r = ((double) mw / mh) / canvasAr;
        double[][] anchors = (r > MAX_ASPECT_DRIFT || r < 1 / MAX_ASPECT_DRIFT)
                ? new double[][]{{1, 1}}
                : new double[][]{
                        {1, 1},
                        {Math.max(1, r), Math.max(1, 1 / r)},
                        {Math.min(1, r), Math.min(1, 1 / r)},
                };

        // One grid pixel of the map being scored, in normalized units. Off the
        // grid actually built, not off GRID: a small canvas gets a smaller grid,
        // and searching finer than the map it scores against only costs time.
        double step = 1.0 / Math.max(gw, gh);
        int coarseSteps = (int) Math.round(MAX_OFFSET / (2 * step));

        double bestScore = base;
        double bestSx = 1, bestSy = 1, bestOx = 0, bestOy = 0;

        // Pass 1 — translation only, per anchor. Finds the basin.
        for (double[] a : anchors) {
            for (int iy = -coarseSteps; iy <= coarseSteps; iy++) {
                for (int ix = -coarseSteps; ix <= coarseSteps; ix++) {
                    double ox = ix * 2 * step;
                    double oy = iy * 2 * step;
                    double s = score(points, edges, gw, gh, a[0], a[1], ox, oy);
                    if (s > bestScore) {
                        bestScore = s;
                        bestSx = a[0];
                        bestSy = a[1];
                        bestOx = ox;
                        bestOy = oy;
                    }
                }
            }
        }

        // Pass 2 — refine size and translation around the winner.
        double anchorSx = bestSx, anchorSy = bestSy, anchorOx = bestOx, anchorOy = bestOy;
        int jitterSteps = (int) Math.round(MAX_SCALE_JITTER / SCALE_STEP);
        for (int jy = -jitterSteps; jy <= jitterSteps; jy++) {
            for (int jx = -jitterSteps; jx <= jitterSteps; jx++) {
                double sx = anchorSx * (1 + jx * SCALE_STEP);
                double sy = anchorSy * (1 + jy * SCALE_STEP);
                for (int iy = -2; iy <= 2; iy++) {
                    for (int ix = -2; ix <= 2; ix++) {
                        double ox = anchorOx + ix * step;
                        double oy = anchorOy + iy * step;
                        if (Math.abs(ox) > MAX_OFFSET || Math.abs(oy) > MAX_OFFSET) continue;
                        double s = score(points, edges, gw, gh, sx, sy, ox, oy);
                        if (s > bestScore) {
                            bestScore = s;
                            bestSx = sx;
                            bestSy = sy;
                            bestOx = ox;
                            bestOy = oy;
                        }
                    }
                }
            }
        }

        // The mask as drawn is the default, and it keeps that status unless the
        // fit is a clear improvement rather than a coin toss.
        if (bestScore < base * MIN_GAIN) return Fit.identity();
        return new Fit(bestSx, bestSy, bestOx, bestOy, bestScore, base);
    }

    /**
     * Mean canvas-edge strength under the mask's boundary for one candidate.
     * Boundary points pushed outside the frame contribute nothing, so a
     * candidate that wins by shoving half the mask off-canvas loses on the
     * average it is scored by.
     */
    private static double score(float[] points, float[] edges, int gw, int gh,
                                double sx, double sy, double ox, double oy) {
        int n = points.length / 2;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double u = 0.5 + (points[2 * i] - 0.5) * sx + ox;
            double v = 0.5 + (points[2 * i + 1] - 0.5) * sy + oy;
            int x = (int) (u * gw);
            int y = (int) (v * gh);
            if (x < 0 || x >= gw || y < 0 || y >= gh) continue;
            sum += edges[y * gw + x];
        }
        return sum / n;
    }

    /**
     * Gradient magnitude of the canvas on the score grid, blurred and
     * normalized to roughly 0..1.
     *
     * <p>The blur is what makes the search work at all: a bare gradient is a
     * one-pixel ridge, so every candidate that isn't already correct scores
     * zero and there is no slope to follow. Spreading it over a few pixels
     * gives each real edge a basin that a nearby candidate can fall into.
     */
    private static float[] edgeMap(BufferedImage canvas, int gw, int gh) {
        BufferedImage gray = new BufferedImage(gw, gh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(canvas, 0, 0, gw, gh, null);
        g.dispose();
        byte[] px = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();

        float[] mag = new float[gw * gh];
        for (int y = 1; y < gh - 1; y++) {
            for (int x = 1; x < gw - 1; x++) {
                int i = y * gw + x;
                int tl = px[i - gw - 1] & 0xff, tc = px[i - gw] & 0xff, tr = px[i - gw + 1] & 0xff;
                int ml = px[i - 1] & 0xff, mr = px[i + 1] & 0xff;
                int bl = px[i + gw - 1] & 0xff, bc = px[i + gw] & 0xff, br = px[i + gw + 1] & 0xff;
                int dx = (tr + 2 * mr + br) - (tl + 2 * ml + bl);
                int dy = (bl + 2 * bc + br) - (tl + 2 * tc + tr);
                mag[i] = (float) Math.hypot(dx, dy);
            }
        }

        float[] blurred = boxBlur(mag, gw, gh, 2);

        // Normalize against a high percentile rather than the maximum: one
        // specular highlight must not flatten every architectural edge to zero.
        float[] sorted = blurred.clone();
        java.util.Arrays.sort(sorted);
        float ref = sorted[Math.min(sorted.length - 1,
                (int) (sorted.length * EDGE_PERCENTILE))];
        if (ref <= 0) return new float[gw * gh];
        for (int i = 0; i < blurred.length; i++) {
            blurred[i] = Math.min(1f, blurred[i] / ref);
        }
        return blurred;
    }

    /** Separable box blur, {@code radius} px each way. */
    private static float[] boxBlur(float[] src, int w, int h, int radius) {
        float[] tmp = new float[w * h];
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int d = -radius; d <= radius; d++) {
                    int nx = x + d;
                    if (nx < 0 || nx >= w) continue;
                    sum += src[y * w + nx];
                    n++;
                }
                tmp[y * w + x] = sum / n;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int d = -radius; d <= radius; d++) {
                    int ny = y + d;
                    if (ny < 0 || ny >= h) continue;
                    sum += tmp[ny * w + x];
                    n++;
                }
                out[y * w + x] = sum / n;
            }
        }
        return out;
    }

    /**
     * Traces the mask's category boundaries and returns them as normalized
     * {@code u,v} pairs in the mask's own frame.
     *
     * <p>Sampled with NEAREST neighbour, never bilinear: a smooth downsample
     * of a colour-block image invents mixed colours along every boundary,
     * which is exactly the region {@link MaskProcessor#classify} would then
     * read wrong. Points on the frame's rim are dropped — a block running off
     * the edge of the image has a boundary there whether or not it is aligned,
     * so scoring it only adds a constant.
     */
    private static float[] boundaryPoints(BufferedImage colorMask) {
        int mw = colorMask.getWidth(), mh = colorMask.getHeight();
        double ar = (double) mw / mh;
        int bw, bh;
        if (ar >= 1) {
            bw = Math.min(MASK_GRID, mw);
            bh = Math.max(1, (int) Math.round(bw / ar));
        } else {
            bh = Math.min(MASK_GRID, mh);
            bw = Math.max(1, (int) Math.round(bh * ar));
        }
        if (bw < 8 || bh < 8) return new float[0];

        byte[] labels = new byte[bw * bh];
        for (int y = 0; y < bh; y++) {
            int sy = Math.min(mh - 1, (int) ((y + 0.5) * mh / bh));
            for (int x = 0; x < bw; x++) {
                int sx = Math.min(mw - 1, (int) ((x + 0.5) * mw / bw));
                labels[y * bw + x] = MaskProcessor.classify(colorMask.getRGB(sx, sy));
            }
        }

        java.util.List<float[]> found = new java.util.ArrayList<>();
        for (int y = 1; y < bh - 1; y++) {
            for (int x = 1; x < bw - 1; x++) {
                byte here = labels[y * bw + x];
                if (here == labels[y * bw + x + 1] && here == labels[(y + 1) * bw + x]) continue;
                found.add(new float[]{(float) ((x + 0.5) / bw), (float) ((y + 0.5) / bh)});
            }
        }
        if (found.isEmpty()) return new float[0];

        // Even stride rather than a random sample: a boundary is traced in
        // scan order, so every stride keeps points from every wall instead of
        // over-weighting whichever one happens to be longest.
        int stride = Math.max(1, found.size() / MAX_POINTS);
        int n = (found.size() + stride - 1) / stride;
        float[] out = new float[n * 2];
        int i = 0;
        for (int k = 0; k < found.size(); k += stride) {
            out[i++] = found.get(k)[0];
            out[i++] = found.get(k)[1];
        }
        return java.util.Arrays.copyOf(out, i);
    }
}
