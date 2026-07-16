package com.gridstore.huevista.project.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Boundary straightening for AI-generated region masks.
 *
 * <p>The colour-coded mask models (Nano Banana / FLUX) draw surfaces with
 * wobbly, hand-painted boundaries: a parapet band whose top line drifts up
 * and down, a tower corner that staircases. Architectural edges are straight
 * in the photo, so the wobble reads as sloppy painting once the region is
 * recoloured. The {@link MaskRefiner} edge snap can only correct a boundary
 * within a few pixels of a real photo edge — it cannot straighten a line
 * that wanders more than the snap band, nor one along a boundary with no
 * usable contrast.
 *
 * <p>This pass regularises the mask's geometry directly:
 * <ol>
 *   <li>trace every boundary loop of the binary mask (outer outlines AND
 *       hole outlines) as a closed polygon on the pixel-corner grid;</li>
 *   <li>segment each loop with Douglas–Peucker — wobble smaller than the
 *       tolerance collapses onto one segment, while genuine corners
 *       (deviation larger than the tolerance) are kept;</li>
 *   <li>least-squares refit each segment's line over the raw contour
 *       vertices it replaced and move every corner to the intersection of
 *       its two fitted lines — so a straight run passes through the MIDDLE
 *       of the wobble instead of being pinned to its extremes, and corners
 *       land where the true edges cross;</li>
 *   <li>refill the polygons (even-odd rule, so holes survive).</li>
 * </ol>
 *
 * Run BEFORE the edge snap: the straightened line is a far better prior, and
 * the guided-filter snap then re-attaches it to the photo's true edge
 * wherever the canvas has contrast.
 *
 * <p>Guard rails:
 * <ul>
 *   <li>the tolerance is capped per loop at a fraction of the loop's thinner
 *       bounding-box side, so thin features (railing gaps, narrow trim
 *       bands) are regularised, never erased;</li>
 *   <li>a refit corner may move at most a couple of tolerances from its
 *       Douglas–Peucker position (near-parallel fitted lines would otherwise
 *       send the intersection to infinity);</li>
 *   <li>a change budget proportional to the mask's boundary length rejects
 *       the result if straightening moved more area than boundary wobble
 *       can explain — any topology mistake falls back to the input;</li>
 *   <li>output keeps the input's resolution and the binary stored-mask
 *       contract.</li>
 * </ul>
 *
 * All geometry code is on plain arrays (package-private statics) so it is
 * unit-testable without image fixtures.
 */
final class MaskStraightener {

    private MaskStraightener() {}

    /** Douglas–Peucker tolerance as a fraction of the mask's longest side.
     *  Must exceed roughly TWICE the model's wobble amplitude (a chord
     *  anchored at a wobble peak sees the opposite peak at 2× amplitude) —
     *  ~12px at the 2048px stored-mask resolution flattens the few-px hand
     *  wobble while keeping real corners like parapet crenellations, which
     *  run tens of pixels deep. */
    static final double EPSILON_FRACTION = 0.006;
    static final double MIN_EPSILON_PX = 3.0;
    static final double MAX_EPSILON_PX = 14.0;
    /** Per-loop cap: tolerance may not exceed this fraction of the loop's
     *  thinner bounding-box side, so a 12px trim band or railing gap keeps
     *  its two long edges instead of collapsing to a sliver. */
    static final double LOOP_EPSILON_BBOX_FRACTION = 0.25;
    /** Change budget per boundary pixel: straightening only moves the
     *  boundary within ~epsilon, so total changed area beyond
     *  perimeter × (epsilon + this slack) means something went wrong and the
     *  input is returned unchanged. */
    static final double CHANGE_BUDGET_SLACK_PX = 2.0;

    // Directions on the pixel-corner grid: 0=+x, 1=+y, 2=-x, 3=-y.
    private static final int[] DIR_DX = {1, 0, -1, 0};
    private static final int[] DIR_DY = {0, 1, 0, -1};

    /**
     * Straightens a binary mask PNG's boundaries. Same-shape transform: the
     * result is a binary grayscale PNG at the input's own resolution. Falls
     * back to the input bytes when the mask is blank or the change budget is
     * exceeded.
     *
     * @throws IOException if the mask cannot be decoded/encoded — callers
     *                     keep the unstraightened mask.
     */
    static byte[] straighten(byte[] maskBytes) throws IOException {
        BufferedImage img = MaskProcessor.decode(maskBytes);
        int w = img.getWidth(), h = img.getHeight();
        boolean[] bin = MaskProcessor.thresholdToBinary(img, w, h);
        double epsilon = epsilonFor(w, h);
        boolean[] straightened = straighten(bin, w, h, epsilon);
        if (!changedWithinBudget(bin, straightened, w, h, epsilon)) {
            return maskBytes;
        }
        return MaskProcessor.encodeBinaryPng(straightened, w, h);
    }

    /** Resolution-adaptive Douglas–Peucker tolerance. */
    static double epsilonFor(int w, int h) {
        double eps = EPSILON_FRACTION * Math.max(w, h);
        return Math.max(MIN_EPSILON_PX, Math.min(MAX_EPSILON_PX, eps));
    }

    /** Trace → segment → refit → refill on a binary field. Pure geometry.
     *  Simplification runs twice: the first pass's refit centres each
     *  straight run through its wobble, halving the residual deviation, so
     *  the second pass collapses kinks the first one had to keep. */
    static boolean[] straighten(boolean[] bin, int w, int h, double epsilon) {
        List<int[]> loops = traceBoundaryLoops(bin, w, h);
        if (loops.isEmpty()) return bin.clone(); // blank mask — nothing to do
        List<double[]> simplified = new ArrayList<>(loops.size());
        for (int[] loop : loops) {
            double[] poly = toDoubles(loop);
            for (int pass = 0; pass < 2; pass++) {
                double[] next = simplifyLoop(poly, epsilon);
                boolean converged = next.length == poly.length;
                poly = next;
                if (converged) break;
            }
            simplified.add(poly);
        }
        return fillEvenOdd(simplified, w, h);
    }

    /**
     * Accepts the straightened mask only when the changed area is explainable
     * by boundary movement: at most perimeter × (epsilon + slack) pixels. A
     * broken loop or fill would change far more and is rejected.
     */
    static boolean changedWithinBudget(boolean[] before, boolean[] after, int w, int h, double epsilon) {
        long changed = 0;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) changed++;
        }
        if (changed == 0) return true;
        long perimeter = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!before[y * w + x]) continue;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || !before[y * w + x - 1] || !before[y * w + x + 1]
                        || !before[(y - 1) * w + x] || !before[(y + 1) * w + x]) {
                    perimeter++;
                }
            }
        }
        return changed <= perimeter * (epsilon + CHANGE_BUDGET_SLACK_PX);
    }

    /**
     * Extracts every boundary loop of the mask as a closed polygon of
     * pixel-corner coordinates ({@code [x0,y0, x1,y1, ...]}, last vertex
     * implicitly connects to the first). Each boundary segment between a
     * foreground pixel and background (or the image border) becomes one
     * directed edge — interior consistently on the traversal's right — and
     * the edges are linked into loops. Collinear runs are merged while
     * walking, so a straight edge contributes just its two endpoints.
     */
    static List<int[]> traceBoundaryLoops(boolean[] bin, int w, int h) {
        Map<Integer, Integer> outgoing = new HashMap<>();
        int stride = w + 1; // corner id = y * stride + x on the (w+1)×(h+1) corner grid
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!bin[y * w + x]) continue;
                if (y == 0 || !bin[(y - 1) * w + x]) {         // top side: (x+1,y) → (x,y)
                    addEdge(outgoing, y * stride + x + 1, 2);
                }
                if (y == h - 1 || !bin[(y + 1) * w + x]) {     // bottom: (x,y+1) → (x+1,y+1)
                    addEdge(outgoing, (y + 1) * stride + x, 0);
                }
                if (x == 0 || !bin[y * w + x - 1]) {           // left: (x,y) → (x,y+1)
                    addEdge(outgoing, y * stride + x, 1);
                }
                if (x == w - 1 || !bin[y * w + x + 1]) {       // right: (x+1,y+1) → (x+1,y)
                    addEdge(outgoing, (y + 1) * stride + x + 1, 3);
                }
            }
        }

        List<int[]> loops = new ArrayList<>();
        while (!outgoing.isEmpty()) {
            Map.Entry<Integer, Integer> entry = outgoing.entrySet().iterator().next();
            int start = entry.getKey();
            int dir = Integer.numberOfTrailingZeros(entry.getValue());
            loops.add(walkLoop(outgoing, start, dir, stride));
        }
        return loops;
    }

    private static void addEdge(Map<Integer, Integer> outgoing, int corner, int dir) {
        outgoing.merge(corner, 1 << dir, (a, b) -> a | b);
    }

    /** Follows directed boundary edges from {@code start} until the walk
     *  returns to it, consuming each edge and recording a vertex at every
     *  direction change. */
    private static int[] walkLoop(Map<Integer, Integer> outgoing, int start, int startDir, int stride) {
        int[] pts = new int[64];
        int n = 0;
        int corner = start;
        int dir = startDir;
        int lastDir = -1;
        while (true) {
            consumeEdge(outgoing, corner, dir);
            if (dir != lastDir) {
                if (n + 2 > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
                pts[n++] = corner % stride;
                pts[n++] = corner / stride;
                lastDir = dir;
            }
            corner += DIR_DX[dir] + DIR_DY[dir] * stride;
            if (corner == start) break;
            int bits = outgoing.getOrDefault(corner, 0);
            dir = chooseNext(bits, dir);
        }
        return Arrays.copyOf(pts, n);
    }

    private static void consumeEdge(Map<Integer, Integer> outgoing, int corner, int dir) {
        int bits = outgoing.getOrDefault(corner, 0) & ~(1 << dir);
        if (bits == 0) {
            outgoing.remove(corner);
        } else {
            outgoing.put(corner, bits);
        }
    }

    /** Next direction at a corner: prefer the turn toward the interior
     *  (right), then straight, then away — a consistent rule that pairs the
     *  four edges of a diagonal-touch corner into non-crossing loops. */
    private static int chooseNext(int bits, int dir) {
        int right = (dir + 1) & 3;
        if ((bits & (1 << right)) != 0) return right;
        if ((bits & (1 << dir)) != 0) return dir;
        int left = (dir + 3) & 3;
        if ((bits & (1 << left)) != 0) return left;
        throw new IllegalStateException("Mask boundary topology broken — no outgoing edge at corner");
    }

    /**
     * Straightens one closed loop: Douglas–Peucker picks which vertices are
     * genuine corners, then each straight run between corners is re-fitted by
     * least squares over ALL the raw vertices it spans and the corners are
     * moved to the intersections of adjacent fitted lines. The ring is split
     * for DP at its two (approximately) farthest-apart vertices — real
     * extreme corners — so no arbitrary start point pins a kink into a
     * straight edge.
     */
    static double[] simplifyLoop(double[] loop, double epsilon) {
        int n = loop.length / 2;
        if (n <= 4) return loop; // a rectangle is already minimal

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, loop[2 * i]);
            maxX = Math.max(maxX, loop[2 * i]);
            minY = Math.min(minY, loop[2 * i + 1]);
            maxY = Math.max(maxY, loop[2 * i + 1]);
        }
        double thin = Math.min(maxX - minX, maxY - minY);
        double eps = Math.min(epsilon, Math.max(1.0, thin * LOOP_EPSILON_BBOX_FRACTION));

        int b = farthestFrom(loop, 0);
        int a = farthestFrom(loop, b);
        if (a == b) return loop;

        boolean[] keep = new boolean[n];
        keep[a] = true;
        keep[b] = true;
        dpMark(loop, a, b, n, eps, keep);
        dpMark(loop, b, a, n, eps, keep);

        int[] corners = new int[n];
        int m = 0;
        for (int p = 0; p < n; p++) {
            int i = (a + p) % n;
            if (keep[i]) corners[m++] = i;
        }
        if (m < 3) return loop; // degenerate — keep the raw ring
        return refitCorners(loop, n, Arrays.copyOf(corners, m), eps);
    }

    private static double[] toDoubles(int[] loop) {
        double[] out = new double[loop.length];
        for (int i = 0; i < loop.length; i++) out[i] = loop[i];
        return out;
    }

    /**
     * Total-least-squares refit: for each run between consecutive kept
     * corners, fit a line through every raw vertex of that run; each corner
     * then moves to the intersection of its two neighbouring fitted lines.
     * A straight edge ends up centred through its wobble instead of pinned
     * to wobble extremes. Corners whose fitted lines are near-parallel (or
     * whose intersection lands unreasonably far away) keep their original
     * position.
     */
    private static double[] refitCorners(double[] loop, int n, int[] corners, double eps) {
        int m = corners.length;
        double[][] lines = new double[m][];
        for (int s = 0; s < m; s++) {
            lines[s] = fitLine(loop, n, corners[s], corners[(s + 1) % m]);
        }
        double maxShift = 2 * eps + 2;
        double[] out = new double[2 * m];
        for (int s = 0; s < m; s++) {
            int cornerIdx = corners[s];
            double ox = loop[2 * cornerIdx], oy = loop[2 * cornerIdx + 1];
            // Corner s joins the run ending here (s-1) and the run starting here (s).
            double[] p = intersect(lines[(s - 1 + m) % m], lines[s]);
            if (p != null && Math.hypot(p[0] - ox, p[1] - oy) <= maxShift) {
                out[2 * s] = p[0];
                out[2 * s + 1] = p[1];
            } else {
                out[2 * s] = ox;
                out[2 * s + 1] = oy;
            }
        }
        return out;
    }

    /** Fits a total-least-squares line (centroid + principal direction) over
     *  the ring range {@code from..to} inclusive. */
    private static double[] fitLine(double[] loop, int n, int from, int to) {
        int len = ((to - from) % n + n) % n;
        int count = len + 1;
        double sx = 0, sy = 0;
        for (int p = 0; p < count; p++) {
            int i = (from + p) % n;
            sx += loop[2 * i];
            sy += loop[2 * i + 1];
        }
        double cx = sx / count, cy = sy / count;
        double sxx = 0, sxy = 0, syy = 0;
        for (int p = 0; p < count; p++) {
            int i = (from + p) % n;
            double dx = loop[2 * i] - cx, dy = loop[2 * i + 1] - cy;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        return new double[]{cx, cy, Math.cos(theta), Math.sin(theta)};
    }

    /** Intersection of two centroid+direction lines, or null when they are
     *  near-parallel. */
    private static double[] intersect(double[] l1, double[] l2) {
        double det = l1[2] * l2[3] - l1[3] * l2[2];
        if (Math.abs(det) < 1e-6) return null;
        double bx = l2[0] - l1[0], by = l2[1] - l1[1];
        double t = (bx * l2[3] - by * l2[2]) / det;
        return new double[]{l1[0] + t * l1[2], l1[1] + t * l1[3]};
    }

    /** Index of the vertex farthest from vertex {@code from}. */
    private static int farthestFrom(double[] loop, int from) {
        double fx = loop[2 * from], fy = loop[2 * from + 1];
        int best = from;
        double bestD = -1;
        for (int i = 0; i < loop.length / 2; i++) {
            double dx = loop[2 * i] - fx, dy = loop[2 * i + 1] - fy;
            double d = dx * dx + dy * dy;
            if (d > bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Iterative Douglas–Peucker over the ring chain {@code from → to}
     *  (walking forward, indices mod n), marking kept vertices. */
    private static void dpMark(double[] loop, int from, int to, int n, double eps, boolean[] keep) {
        int len = ((to - from) % n + n) % n; // segments along the chain
        if (len < 2) return;
        Deque<int[]> ranges = new ArrayDeque<>();
        ranges.push(new int[]{0, len});
        while (!ranges.isEmpty()) {
            int[] range = ranges.pop();
            int lo = range[0], hi = range[1];
            if (hi - lo < 2) continue;
            int i0 = (from + lo) % n, i1 = (from + hi) % n;
            double x0 = loop[2 * i0], y0 = loop[2 * i0 + 1];
            double x1 = loop[2 * i1], y1 = loop[2 * i1 + 1];
            double worst = -1;
            int worstPos = -1;
            for (int p = lo + 1; p < hi; p++) {
                int i = (from + p) % n;
                double d = pointSegDist(loop[2 * i], loop[2 * i + 1], x0, y0, x1, y1);
                if (d > worst) {
                    worst = d;
                    worstPos = p;
                }
            }
            if (worst > eps) {
                keep[(from + worstPos) % n] = true;
                ranges.push(new int[]{lo, worstPos});
                ranges.push(new int[]{worstPos, hi});
            }
        }
    }

    private static double pointSegDist(double px, double py,
                                       double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) return Math.hypot(px - x0, py - y0);
        double t = ((px - x0) * dx + (py - y0) * dy) / len2;
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
    }

    /**
     * Rasterises the polygons back into a binary field with the even-odd
     * rule: a pixel is foreground when its centre lies inside an odd number
     * of loop crossings. Holes (loops inside loops) come out empty exactly
     * as traced. Scanlines sample at pixel centres (y + 0.5) with a
     * half-open [ymin, ymax) rule per edge, so shared vertices are counted
     * consistently.
     */
    static boolean[] fillEvenOdd(List<double[]> loops, int w, int h) {
        int[] counts = new int[h];
        for (double[] loop : loops) {
            int n = loop.length / 2;
            for (int i = 0; i < n; i++) {
                double y1 = loop[2 * i + 1];
                double y2 = loop[2 * ((i + 1) % n) + 1];
                if (y1 == y2) continue;
                int lo = Math.max(0, rowFor(Math.min(y1, y2)));
                int hi = Math.min(h, rowFor(Math.max(y1, y2)));
                for (int y = lo; y < hi; y++) counts[y]++;
            }
        }
        double[][] crossings = new double[h][];
        int[] filled = new int[h];
        for (int y = 0; y < h; y++) crossings[y] = new double[counts[y]];

        for (double[] loop : loops) {
            int n = loop.length / 2;
            for (int i = 0; i < n; i++) {
                double x1 = loop[2 * i], y1 = loop[2 * i + 1];
                int j = (i + 1) % n;
                double x2 = loop[2 * j], y2 = loop[2 * j + 1];
                if (y1 == y2) continue;
                int lo = Math.max(0, rowFor(Math.min(y1, y2)));
                int hi = Math.min(h, rowFor(Math.max(y1, y2)));
                double dxdy = (x2 - x1) / (y2 - y1);
                for (int y = lo; y < hi; y++) {
                    crossings[y][filled[y]++] = x1 + (y + 0.5 - y1) * dxdy;
                }
            }
        }

        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            double[] row = crossings[y];
            Arrays.sort(row, 0, filled[y]);
            for (int k = 0; k + 1 < filled[y]; k += 2) {
                int xStart = Math.max(0, (int) Math.ceil(row[k] - 0.5));
                int xEnd = Math.min(w - 1, (int) Math.ceil(row[k + 1] - 0.5) - 1);
                for (int x = xStart; x <= xEnd; x++) out[y * w + x] = true;
            }
        }
        return out;
    }

    /** First scanline row whose centre (row + 0.5) is at or above {@code y}. */
    private static int rowFor(double y) {
        return (int) Math.ceil(y - 0.5);
    }
}
