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
 * image that merely looks like the input. Three things routinely go wrong
 * with that, and all of them land as "the mask is off the wall":
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
 *   <li><b>Local drift.</b> The repaint is not a rigid copy of the photo. A
 *       generative model reproduces each part of the scene about as well as it
 *       can and the errors do NOT agree across the frame: on a real facade the
 *       colour blocks can sit within a pixel of the windows halfway up the
 *       wall while the parapet is 2% of the frame high and the boundary wall
 *       at the bottom is 3% low. One rigid correction cannot absorb that. It
 *       splits the difference — pulling the parts that were already right off
 *       their surfaces — or, because the parts that are right dilute the
 *       average it is scored by, it fails {@link #MIN_GAIN} and does nothing
 *       at all, which is what leaves paint over the sky along a roofline.</li>
 * </ul>
 *
 * <p>The first two are rigid, whole-frame errors, so one rigid correction
 * fixes them: find the scale and translation that best drop the mask's
 * colour-block BOUNDARIES onto the canvas's own EDGES. A wall's outline in the
 * mask should sit on the wall's outline in the photo; when it doesn't, the
 * offset that makes it fit is the offset the model introduced.
 *
 * <p>The third needs the same measurement made in more than one place. After
 * the rigid fit the frame is divided into a coarse grid of cells and each cell
 * is re-measured on its OWN boundaries, giving a small displacement per cell;
 * the cells are then filled where they had nothing to measure, smoothed so the
 * result stays continuous, and carried as a {@link Warp} — a lattice the
 * resampler interpolates, so the mask is nudged by a little more here and a
 * little less there in one pass. Every cell competes only with itself, so a
 * roofline that is genuinely 2% out is no longer outvoted by the windows that
 * were already right.
 *
 * <p>The search is deliberately small and heavily guarded. The rigid part only
 * ever produces a scale/translate — it cannot reshape a region the model
 * didn't draw — capped at {@link #MAX_OFFSET} of the frame and
 * {@link #MAX_SCALE_JITTER} of the size, and discarded unless it beats leaving
 * the mask alone by {@link #MIN_GAIN}. The local part is capped again at
 * {@link #MAX_LOCAL_OFFSET} per cell on top of that, each cell has to beat
 * standing still by {@link #MIN_CELL_GAIN} on evidence of its own
 * ({@link #MIN_CELL_POINTS} samples, {@link #MIN_CELL_SCORE} of edge under
 * them), and the finished field is thrown away unless it beats the rigid fit
 * over the WHOLE frame by {@link #MIN_FIELD_GAIN}. A photo with nothing to
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

    /** Cells across the frame's LONGER side in the local pass; the shorter side
     *  gets however many keeps them roughly square, never fewer than two.
     *  Coarse on purpose — a cell has to hold enough of the mask's boundary to
     *  be measurable, and a fine grid would be measuring noise per cell. */
    private static final int LOCAL_CELLS_LONG = 6;
    private static final int MIN_LOCAL_CELLS = 2;

    /** Hard cap on the LOCAL correction, per cell, as a share of the frame —
     *  on top of whatever the rigid fit already did. Sized to the drift a
     *  repaint actually shows (a few percent), not to what a search could
     *  chase: past this a "correction" is the field hunting for some other
     *  wall's edge, and a mask on the wrong wall is worse than one a little
     *  off its own. */
    private static final double MAX_LOCAL_OFFSET = 0.03;

    /** Boundary samples a cell needs before its own measurement is believed.
     *  A cell below it is left unmeasured and takes its displacement from the
     *  neighbours instead, which is how a cell of blank sky follows the wall
     *  beside it rather than tearing away from it. */
    private static final int MIN_CELL_POINTS = 40;

    /** A cell's move has to beat standing still by this factor. Higher than
     *  {@link #MIN_GAIN}: a cell is scored on its own boundaries only, so a
     *  real local drift shows up as a large relative gain there — the small
     *  ones are the noise this is filtering out. */
    private static final double MIN_CELL_GAIN = 1.10;

    /** ...and land on this much canvas edge in absolute terms. Without it a
     *  cell whose boundary sits on nothing could "gain" any factor at all by
     *  finding a marginally less empty spot. */
    private static final double MIN_CELL_SCORE = 0.04;

    /** The finished field has to beat the rigid fit over the WHOLE frame by
     *  this much, or it is dropped and the rigid fit ships alone. Modest,
     *  because every cell in it already passed a much stiffer test of its
     *  own; this is the check that the cells did not disagree their way into
     *  a field that is worse than no field. */
    private static final double MIN_FIELD_GAIN = 1.02;

    /** Measured neighbours a cell needs before its value may be replaced by the
     *  neighbourhood median. Below it there is no majority to be an outlier
     *  against and the cell keeps what it measured. */
    private static final int MEDIAN_MIN_NEIGHBOURS = 2;

    /** How much a cell's score must fall when its winning placement is nudged
     *  one step along an axis before that axis counts as MEASURED there, as a
     *  share of the score.
     *
     *  <p>This is the aperture problem, and ignoring it makes a field like
     *  this useless: a cell holding only the horizontal underside of a slab
     *  says nothing at all about horizontal drift — sliding it sideways leaves
     *  it on the same edge and scores exactly the same — so its "answer" of
     *  zero is not a measurement, it is the absence of one. Recorded as such,
     *  the cell takes its horizontal displacement from the neighbours that
     *  could see a vertical edge, instead of voting their real measurement
     *  down to nothing. */
    private static final double AXIS_MIN_DROP = 0.02;

    /** A field whose every node moves less than this is no field: it would
     *  resample the mask for nothing. In shares of the frame. */
    private static final double FIELD_EPSILON = 1e-4;

    /**
     * The extra, position-dependent nudge applied on top of the rigid fit: a
     * {@code (cols+1) × (rows+1)} lattice of displacements over the CANVAS
     * frame, bilinearly interpolated between nodes. Values are in shares of
     * the canvas frame, node {@code (i,j)} sitting at {@code u = i/cols,
     * v = j/rows}.
     *
     * <p>Interpolated rather than applied per cell so the correction is
     * continuous: a mask nudged by one amount inside a cell and another amount
     * just across its border would show the join as a step through the middle
     * of a wall. Displacements are small and smoothed, so the map cannot fold
     * back on itself.
     */
    record Warp(int cols, int rows, double[] du, double[] dv) {

        /** The displacement at canvas point {@code (u,v)}, written into
         *  {@code out} as {@code {du, dv}}. Outside the frame the nearest edge
         *  of the lattice is held, so a sample just off-canvas does not fly
         *  off on an extrapolated slope. */
        void displace(double u, double v, double[] out) {
            double fu = Math.min(cols, Math.max(0, u * cols));
            double fv = Math.min(rows, Math.max(0, v * rows));
            int i0 = Math.min(cols - 1, (int) fu);
            int j0 = Math.min(rows - 1, (int) fv);
            double tu = fu - i0;
            double tv = fv - j0;
            int stride = cols + 1;
            int a = j0 * stride + i0, b = a + 1, c = a + stride, d = c + 1;
            out[0] = (du[a] * (1 - tu) + du[b] * tu) * (1 - tv)
                   + (du[c] * (1 - tu) + du[d] * tu) * tv;
            out[1] = (dv[a] * (1 - tu) + dv[b] * tu) * (1 - tv)
                   + (dv[c] * (1 - tu) + dv[d] * tu) * tv;
        }

        /** The largest node displacement, as a share of the frame — what the
         *  logs report so a suspicious field is visible in them. */
        double maxShift() {
            double max = 0;
            for (int i = 0; i < du.length; i++) max = Math.max(max, Math.hypot(du[i], dv[i]));
            return max;
        }

        /** The record's generated form would print two array identities, which
         *  is worse than useless in a log line. */
        @Override
        public String toString() {
            return String.format("warp %d×%d up to %+.3f", cols, rows, maxShift());
        }
    }

    /**
     * The correction to apply when resampling a mask onto the canvas, on top
     * of the plain stretch-to-fill that {@link Fit#identity()} represents.
     *
     * <p>Forward (mask → canvas, both in 0..1 of their own frame):
     * {@code u0 = 0.5 + (u - 0.5) * scaleX + offsetX}, then {@code + warp} at
     * that point when one was measured. The resampler uses the inverse of
     * that — see {@link MaskProcessor#resizeBinaryAligned}.
     *
     * @param warp      per-cell displacements on top of the rigid part, or null for none
     * @param score     the winning fit's mean canvas-edge strength under the mask's boundary
     * @param baseScore the same measure for the untouched mask, for the logs
     */
    record Fit(double scaleX, double scaleY, double offsetX, double offsetY,
               Warp warp, double score, double baseScore) {

        static Fit identity() {
            return new Fit(1, 1, 0, 0, null, 0, 0);
        }

        boolean isIdentity() {
            return scaleX == 1 && scaleY == 1 && offsetX == 0 && offsetY == 0 && warp == null;
        }

        /** How far this fit moves the frame's centre, as a share of the frame. */
        double shift() {
            return Math.hypot(offsetX, offsetY);
        }

        @Override
        public String toString() {
            return String.format("scale %.3f×%.3f, offset %+.3f,%+.3f%s (edge score %.3f vs %.3f)",
                    scaleX, scaleY, offsetX, offsetY,
                    warp == null ? ""
                            : String.format(", local field %d×%d up to %+.3f",
                                    warp.cols(), warp.rows(), warp.maxShift()),
                    score, baseScore);
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
        // rigid fit is a clear improvement rather than a coin toss. Declining
        // it is NOT the end of the measurement: a mask that drifts by
        // different amounts in different parts of the frame has no single
        // rigid answer worth taking, and that is exactly what the local pass
        // below is for. So neutralise the rigid part and carry on rather than
        // returning here.
        boolean rigidAccepted = bestScore >= base * MIN_GAIN;
        if (!rigidAccepted) {
            bestSx = 1; bestSy = 1; bestOx = 0; bestOy = 0;
            bestScore = base;
        }

        // Everything from here compares FINISHED corrections — rigid part and
        // local field together — because the two are not independent. Leaving
        // the mask alone is one of the candidates, and it is the incumbent.
        double winScore = base;
        double winSx = 1, winSy = 1, winOx = 0, winOy = 0;
        Warp winWarp = null;
        if (rigidAccepted) {
            winScore = bestScore;
            winSx = bestSx; winSy = bestSy; winOx = bestOx; winOy = bestOy;
        }

        // Candidate: a local field on top of whatever the rigid part settled on.
        Warp overRigid = localField(points, edges, gw, gh,
                bestSx, bestSy, bestOx, bestOy, bestScore);
        if (overRigid != null) {
            double s = score(points, edges, gw, gh, bestSx, bestSy, bestOx, bestOy, overRigid);
            if (s > winScore) {
                winScore = s;
                winSx = bestSx; winSy = bestSy; winOx = bestOx; winOy = bestOy;
                winWarp = overRigid;
            }
        }

        // Candidate: a local field on the mask exactly as drawn. A rigid fit
        // that wins on the frame's AVERAGE can still be the wrong move for half
        // of it — landing one wall perfectly while pushing another further off
        // scores well and looks terrible — and the local pass then starts from
        // a placement it is capped too tightly to walk back. Measuring the
        // field from where the model actually drew the mask keeps that option
        // open; whichever finished candidate lines up better wins.
        if (rigidAccepted) {
            Warp overDrawn = localField(points, edges, gw, gh, 1, 1, 0, 0, base);
            if (overDrawn != null) {
                double s = score(points, edges, gw, gh, 1, 1, 0, 0, overDrawn);
                if (s > winScore) {
                    winScore = s;
                    winSx = 1; winSy = 1; winOx = 0; winOy = 0;
                    winWarp = overDrawn;
                }
            }
        }

        if (winWarp == null && winSx == 1 && winSy == 1 && winOx == 0 && winOy == 0) {
            return Fit.identity();
        }
        return new Fit(winSx, winSy, winOx, winOy, winWarp, winScore, base);
    }

    /**
     * Re-measures the mask cell by cell after the rigid fit and returns the
     * smooth displacement field that lines each part of it up, or null when
     * there is nothing worth applying.
     *
     * <p>The rigid fit is scored on the average over the whole frame, so it can
     * only answer "where does the mask as a whole belong". This asks the same
     * question of each patch separately: the boundary samples that land in one
     * cell are moved together, over a small window, and kept only if that
     * cell's own edge score improves decisively. Each answer is then held to
     * its neighbours (a median, to drop a cell that locked onto the wrong
     * edge), cells that could not measure an axis take it from the ones that
     * could, and the grid becomes a lattice the resampler interpolates.
     *
     * @param baseScore the whole-frame score of the rigid fit, which the
     *                  finished field has to beat by {@link #MIN_FIELD_GAIN}
     */
    private static Warp localField(float[] points, float[] edges, int gw, int gh,
                                   double sx, double sy, double ox, double oy,
                                   double baseScore) {
        int cols, rows;
        if (gw >= gh) {
            cols = LOCAL_CELLS_LONG;
            rows = Math.max(MIN_LOCAL_CELLS, (int) Math.round(LOCAL_CELLS_LONG * (double) gh / gw));
        } else {
            rows = LOCAL_CELLS_LONG;
            cols = Math.max(MIN_LOCAL_CELLS, (int) Math.round(LOCAL_CELLS_LONG * (double) gw / gh));
        }
        int cells = cols * rows;

        // Every boundary sample where the rigid fit puts it, and which cell
        // that is. Samples the fit pushed off the canvas belong to no cell:
        // they score zero wherever they are moved, so they can only dilute a
        // cell's measurement.
        int n = points.length / 2;
        float[] bu = new float[n];
        float[] bv = new float[n];
        int[] cellOf = new int[n];
        int[] counts = new int[cells];
        for (int i = 0; i < n; i++) {
            double u = 0.5 + (points[2 * i] - 0.5) * sx + ox;
            double v = 0.5 + (points[2 * i + 1] - 0.5) * sy + oy;
            bu[i] = (float) u;
            bv[i] = (float) v;
            if (u < 0 || u >= 1 || v < 0 || v >= 1) {
                cellOf[i] = -1;
                continue;
            }
            int c = Math.min(rows - 1, (int) (v * rows)) * cols + Math.min(cols - 1, (int) (u * cols));
            cellOf[i] = c;
            counts[c]++;
        }

        // Points regrouped per cell, counting-sort style: one flat array plus
        // a start index per cell, so a cell's search touches only its own
        // samples and allocates nothing per candidate.
        int[] start = new int[cells + 1];
        for (int c = 0; c < cells; c++) start[c + 1] = start[c] + counts[c];
        int[] cursor = start.clone();
        int[] byCell = new int[start[cells]];
        for (int i = 0; i < n; i++) {
            if (cellOf[i] >= 0) byCell[cursor[cellOf[i]]++] = i;
        }

        double step = 1.0 / Math.max(gw, gh);
        int[] candidates = offsetCandidates((int) Math.round(MAX_LOCAL_OFFSET / step));

        double[] cellDu = new double[cells];
        double[] cellDv = new double[cells];
        // Per AXIS, not per cell: a cell can pin down one direction and be
        // blind in the other (see AXIS_MIN_DROP), and treating that blindness
        // as a measured zero is what flattens a real field back to nothing.
        boolean[] knowsU = new boolean[cells];
        boolean[] knowsV = new boolean[cells];

        for (int c = 0; c < cells; c++) {
            if (counts[c] < MIN_CELL_POINTS) continue;
            int from = start[c], to = start[c + 1];
            double still = cellScore(byCell, from, to, bu, bv, edges, gw, gh, 0, 0);
            double bar = Math.max(still * MIN_CELL_GAIN, MIN_CELL_SCORE);
            double best = still;
            double bestDu = 0, bestDv = 0;
            // Candidates come in rings of increasing size and the comparison is
            // strict, so of two placements that score alike the smaller move
            // wins — a cell that is already right stays where it is.
            for (int k = 0; k < candidates.length; k += 2) {
                double du = candidates[k] * step;
                double dv = candidates[k + 1] * step;
                double sc = cellScore(byCell, from, to, bu, bv, edges, gw, gh, du, dv);
                if (sc > best && sc >= bar) {
                    best = sc;
                    bestDu = du;
                    bestDv = dv;
                }
            }
            // A cell that ends up sitting on nothing has not found its surface,
            // it has run out of frame; its answer is not evidence for anybody.
            if (best < MIN_CELL_SCORE) continue;

            // Which axes this cell can actually see, by nudging the winner one
            // step each way: an axis the score does not care about is one this
            // cell cannot speak for.
            double uDrop = best - Math.min(
                    cellScore(byCell, from, to, bu, bv, edges, gw, gh, bestDu - step, bestDv),
                    cellScore(byCell, from, to, bu, bv, edges, gw, gh, bestDu + step, bestDv));
            double vDrop = best - Math.min(
                    cellScore(byCell, from, to, bu, bv, edges, gw, gh, bestDu, bestDv - step),
                    cellScore(byCell, from, to, bu, bv, edges, gw, gh, bestDu, bestDv + step));
            // Recorded even when the answer is zero: "this cell is already
            // where it belongs" is a measurement, and one its neighbours
            // should be able to see.
            knowsU[c] = uDrop >= AXIS_MIN_DROP * best;
            knowsV[c] = vDrop >= AXIS_MIN_DROP * best;
            cellDu[c] = knowsU[c] ? bestDu : 0;
            cellDv[c] = knowsV[c] ? bestDv : 0;
        }
        if (!any(knowsU) && !any(knowsV)) return null;

        // Outlier rejection, before anything is filled in from these values: a
        // cell whose search locked onto some other surface's edge is replaced
        // by the median of itself and the neighbours that can see the same
        // axis. A median, not an average — on a grid this coarse an average
        // drags a wall that drifted left toward the one beside it that drifted
        // right until neither is corrected, while the median keeps whichever
        // answer the neighbourhood actually agrees on.
        medianFilter(cellDu, knowsU, cols, rows);
        medianFilter(cellDv, knowsV, cols, rows);

        // Cells blind on an axis follow the cells that could see it, spreading
        // outward one ring at a time so a patch of sky takes the drift of the
        // roofline under it rather than the frame's average — which, when one
        // half of a facade drifted one way and the other half the other, is
        // nobody's answer. Anything the spread never reaches (an island of
        // blind cells) falls back to the average of the sighted ones.
        diffuse(cellDu, knowsU.clone(), cols, rows, mean(cellDu, knowsU));
        diffuse(cellDv, knowsV.clone(), cols, rows, mean(cellDv, knowsV));

        // Cell values become lattice nodes: a node is the average of the cells
        // that meet at it, which is what turns a piecewise-constant grid into
        // a continuous field once the resampler interpolates between nodes.
        int stride = cols + 1;
        double[] du = new double[stride * (rows + 1)];
        double[] dv = new double[stride * (rows + 1)];
        double maxNode = 0;
        for (int j = 0; j <= rows; j++) {
            for (int i = 0; i <= cols; i++) {
                double su = 0, sv = 0;
                int k = 0;
                for (int cj = j - 1; cj <= j; cj++) {
                    for (int ci = i - 1; ci <= i; ci++) {
                        if (ci < 0 || ci >= cols || cj < 0 || cj >= rows) continue;
                        su += cellDu[cj * cols + ci];
                        sv += cellDv[cj * cols + ci];
                        k++;
                    }
                }
                double nu = k == 0 ? 0 : su / k;
                double nv = k == 0 ? 0 : sv / k;
                // Defensive: averaging cannot exceed the per-cell cap, but the
                // cap is the promise this class makes and it is cheap to keep.
                double mag = Math.hypot(nu, nv);
                if (mag > MAX_LOCAL_OFFSET) {
                    nu *= MAX_LOCAL_OFFSET / mag;
                    nv *= MAX_LOCAL_OFFSET / mag;
                    mag = MAX_LOCAL_OFFSET;
                }
                du[j * stride + i] = nu;
                dv[j * stride + i] = nv;
                maxNode = Math.max(maxNode, mag);
            }
        }
        if (maxNode < FIELD_EPSILON) return null;

        Warp warp = new Warp(cols, rows, du, dv);
        // The cells were judged one at a time; this is the only check that
        // they add up to something better than the rigid fit everywhere.
        if (score(points, edges, gw, gh, sx, sy, ox, oy, warp) < baseScore * MIN_FIELD_GAIN) {
            return null;
        }
        return warp;
    }

    /** Candidate cell displacements as {@code dx,dy} step pairs, ordered by how
     *  far they move — so the smallest move that fits wins a tie. */
    private static int[] offsetCandidates(int radius) {
        int side = 2 * radius + 1;
        Integer[] order = new Integer[side * side];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int ax = a % side - radius, ay = a / side - radius;
            int bx = b % side - radius, by = b / side - radius;
            return Integer.compare(ax * ax + ay * ay, bx * bx + by * by);
        });
        int[] out = new int[order.length * 2];
        for (int i = 0; i < order.length; i++) {
            out[2 * i] = order[i] % side - radius;
            out[2 * i + 1] = order[i] / side - radius;
        }
        return out;
    }

    /** Mean canvas-edge strength under one cell's boundary samples, moved by
     *  {@code (du,dv)}. Same convention as {@link #score}: a sample pushed off
     *  the frame contributes nothing but still counts against the average. */
    private static double cellScore(int[] byCell, int from, int to,
                                    float[] bu, float[] bv, float[] edges, int gw, int gh,
                                    double du, double dv) {
        double sum = 0;
        for (int k = from; k < to; k++) {
            int i = byCell[k];
            int x = (int) ((bu[i] + du) * gw);
            int y = (int) ((bv[i] + dv) * gh);
            if (x < 0 || x >= gw || y < 0 || y >= gh) continue;
            sum += edges[y * gw + x];
        }
        return sum / (to - from);
    }

    /**
     * Gives every cell that could not see this axis the average of the
     * 4-neighbours that could, ring by ring outward, so the field stays
     * defined everywhere without inventing a drift of its own. Cells no ring
     * ever reaches take {@code fallback}.
     *
     * <p>{@code known} is modified; pass a copy of the flags.
     */
    private static void diffuse(double[] values, boolean[] known, int cols, int rows,
                                double fallback) {
        int cells = cols * rows;
        // At most this many rings can be needed to cross the grid.
        for (int ring = 0; ring < cols + rows; ring++) {
            boolean progressed = false;
            double[] next = values.clone();
            boolean[] nextKnown = known.clone();
            for (int j = 0; j < rows; j++) {
                for (int i = 0; i < cols; i++) {
                    int c = j * cols + i;
                    if (known[c]) continue;
                    double sum = 0;
                    int k = 0;
                    if (i > 0 && known[c - 1]) { sum += values[c - 1]; k++; }
                    if (i < cols - 1 && known[c + 1]) { sum += values[c + 1]; k++; }
                    if (j > 0 && known[c - cols]) { sum += values[c - cols]; k++; }
                    if (j < rows - 1 && known[c + cols]) { sum += values[c + cols]; k++; }
                    if (k == 0) continue;
                    next[c] = sum / k;
                    nextKnown[c] = true;
                    progressed = true;
                }
            }
            System.arraycopy(next, 0, values, 0, cells);
            System.arraycopy(nextKnown, 0, known, 0, cells);
            if (!progressed) break;
        }
        for (int c = 0; c < cells; c++) {
            if (!known[c]) values[c] = fallback;
        }
    }

    /**
     * Replaces each cell that can see this axis with the median of itself and
     * the 4-neighbours that can see it too, reading from a snapshot so one
     * cell's new value cannot cascade into the next. Cells with fewer than
     * {@link #MEDIAN_MIN_NEIGHBOURS} such neighbours are left alone.
     */
    private static void medianFilter(double[] values, boolean[] known, int cols, int rows) {
        double[] src = values.clone();
        double[] window = new double[5];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int c = j * cols + i;
                if (!known[c]) continue;
                int k = 0;
                window[k++] = src[c];
                if (i > 0 && known[c - 1]) window[k++] = src[c - 1];
                if (i < cols - 1 && known[c + 1]) window[k++] = src[c + 1];
                if (j > 0 && known[c - cols]) window[k++] = src[c - cols];
                if (j < rows - 1 && known[c + cols]) window[k++] = src[c + cols];
                if (k - 1 < MEDIAN_MIN_NEIGHBOURS) continue;
                values[c] = median(window, k);
            }
        }
    }

    /** Whether any cell set a flag. */
    private static boolean any(boolean[] flags) {
        for (boolean f : flags) if (f) return true;
        return false;
    }

    /** Mean of the cells whose flag is set; 0 when none is. */
    private static double mean(double[] values, boolean[] known) {
        double sum = 0;
        int k = 0;
        for (int i = 0; i < values.length; i++) {
            if (!known[i]) continue;
            sum += values[i];
            k++;
        }
        return k == 0 ? 0 : sum / k;
    }

    /** Median of the first {@code k} entries; sorts the array in place. */
    private static double median(double[] values, int k) {
        java.util.Arrays.sort(values, 0, k);
        return (k & 1) == 1 ? values[k / 2] : (values[k / 2 - 1] + values[k / 2]) / 2;
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

    /** {@link #score} with a local field applied on top of the rigid part —
     *  how the finished correction is judged against the rigid one alone. */
    private static double score(float[] points, float[] edges, int gw, int gh,
                                double sx, double sy, double ox, double oy, Warp warp) {
        int n = points.length / 2;
        double[] d = new double[2];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double u = 0.5 + (points[2 * i] - 0.5) * sx + ox;
            double v = 0.5 + (points[2 * i + 1] - 0.5) * sy + oy;
            warp.displace(u, v, d);
            int x = (int) ((u + d[0]) * gw);
            int y = (int) ((v + d[1]) * gh);
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
