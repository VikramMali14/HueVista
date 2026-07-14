package com.gridstore.huevista.project.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Edge snapping: refines a region mask against the canvas photograph so the
 * mask boundary locks onto the photo's REAL edges instead of the model's
 * approximation of them.
 *
 * <p>Even a good colour-coded segmentation sits a pixel or two off the true
 * surface boundary here and there — mask creeping onto a window frame, an
 * unmasked sliver of wall along an edge. This runs a colour guided filter
 * (He et al.) over the mask using the canvas as the guide: within a small
 * band around the mask boundary, coverage re-attaches to wherever the
 * canvas's colours actually change (frames, wall/sky lines, railing bars).
 *
 * <p>This is the server-side twin of the frontend's {@code mask-refine.ts}
 * (same algorithm, same constants), run ONCE at segmentation time so every
 * consumer of the stored masks gets aligned edges without paying the
 * refinement cost on-device. Deliberately conservative, same guard rails:
 *
 * <ul>
 *   <li>the guided alpha is re-steepened, so boundaries with no usable photo
 *       edge (two same-coloured surfaces meeting) stay a tight ramp at the
 *       mask's own line rather than smearing across the filter radius;</li>
 *   <li>the result is clamped inside an eroded/dilated band of the hard
 *       mask, so snapping can fix misregistration but can never migrate a
 *       region onto a different surface;</li>
 *   <li>refinement runs at a capped working resolution and the result is
 *       re-thresholded to a binary mask (the stored-mask contract), so
 *       downstream morph/split/count code sees the format it expects.</li>
 * </ul>
 *
 * All pixel math is on plain float arrays (package-private statics) so it is
 * unit-testable without image fixtures.
 */
final class MaskRefiner {

    private MaskRefiner() {}

    /** Longest side of the working resolution. Alignment quality is set by
     *  edge geometry, not megapixels; ~1000px keeps the filter fast and the
     *  transient float arrays small. Mirrors the frontend's GUIDE_MAX_DIM. */
    static final int WORK_MAX_DIM = 1000;
    /** Guided-filter window radius (working-res px). */
    static final int SNAP_RADIUS = 6;
    /** Guided-filter regularisation (colours normalised to 0..1). */
    static final double SNAP_EPS = 2e-4;
    /** How far (working-res px) snapping may move the mask boundary. */
    static final int SNAP_BAND_PX = 4;
    /** Re-steepening ramp for the guided alpha, centred on 0.5 so the snapped
     *  crossing point stays where the filter put it. */
    static final double STEEPEN_LO = 0.35;
    static final double STEEPEN_HI = 0.65;

    /**
     * Snaps a binary mask PNG onto the canvas's real edges. The mask and the
     * canvas may be any (possibly different) resolutions; the result is a
     * binary grayscale PNG at the MASK's own resolution, so callers can drop
     * this into an existing pipeline as a same-shape transform.
     *
     * @throws IOException if either image cannot be decoded/encoded — callers
     *                     keep the unrefined mask.
     */
    static byte[] snapToCanvas(byte[] maskBytes, BufferedImage canvas) throws IOException {
        BufferedImage mask = MaskProcessor.decode(maskBytes);
        int outW = mask.getWidth(), outH = mask.getHeight();

        double scale = Math.min(1.0, (double) WORK_MAX_DIM / Math.max(canvas.getWidth(), canvas.getHeight()));
        int w = Math.max(1, (int) Math.round(canvas.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(canvas.getHeight() * scale));

        float[][] guide = toRgbPlanes(resizeSmooth(canvas, w, h, BufferedImage.TYPE_INT_RGB));
        float[] hard = toBinaryCoverage(resizeSmooth(mask, w, h, BufferedImage.TYPE_BYTE_GRAY));

        float[] q = guidedFilter(guide[0], guide[1], guide[2], hard, w, h, SNAP_RADIUS, SNAP_EPS);
        float[] alpha = snap(hard, q, w, h, SNAP_BAND_PX);

        // Back to the binary stored-mask contract at the mask's own resolution.
        BufferedImage refined = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) refined.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < alpha.length; i++) {
            data[i] = alpha[i] > 0.5f ? (byte) 0xFF : 0;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(refined, "png", baos)) {
            throw new IOException("PNG encoder not available");
        }
        return MaskProcessor.resizeBinarySmooth(baos.toByteArray(), outW, outH);
    }

    /**
     * Colour guided filter of a coverage field {@code p} (0..1) steered by
     * the guide planes: the output follows {@code p} in flat areas but
     * re-attaches its transitions to the guide's colour edges.
     */
    static float[] guidedFilter(float[] R, float[] G, float[] B, float[] p,
                                int w, int h, int radius, double eps) {
        int n = w * h;
        float[] mR = boxBlur(R, w, h, radius);
        float[] mG = boxBlur(G, w, h, radius);
        float[] mB = boxBlur(B, w, h, radius);
        float[] mP = boxBlur(p, w, h, radius);
        float[] cR = boxBlur(mul(R, p), w, h, radius);
        float[] cG = boxBlur(mul(G, p), w, h, radius);
        float[] cB = boxBlur(mul(B, p), w, h, radius);
        float[] sRR = boxBlur(mul(R, R), w, h, radius);
        float[] sRG = boxBlur(mul(R, G), w, h, radius);
        float[] sRB = boxBlur(mul(R, B), w, h, radius);
        float[] sGG = boxBlur(mul(G, G), w, h, radius);
        float[] sGB = boxBlur(mul(G, B), w, h, radius);
        float[] sBB = boxBlur(mul(B, B), w, h, radius);

        float[] aR = new float[n];
        float[] aG = new float[n];
        float[] aB = new float[n];
        float[] bb = new float[n];
        for (int i = 0; i < n; i++) {
            double rr = sRR[i] - (double) mR[i] * mR[i] + eps;
            double rg = sRG[i] - (double) mR[i] * mG[i];
            double rb = sRB[i] - (double) mR[i] * mB[i];
            double gg = sGG[i] - (double) mG[i] * mG[i] + eps;
            double gb = sGB[i] - (double) mG[i] * mB[i];
            double b2 = sBB[i] - (double) mB[i] * mB[i] + eps;
            double vR = cR[i] - (double) mR[i] * mP[i];
            double vG = cG[i] - (double) mG[i] * mP[i];
            double vB = cB[i] - (double) mB[i] * mP[i];
            // Solve (Σ + eps·I) a = cov via the symmetric matrix's adjugate;
            // eps on the diagonal keeps det strictly positive on flat areas.
            double A = gg * b2 - gb * gb;
            double Bc = rb * gb - rg * b2;
            double C = rg * gb - rb * gg;
            double D = rr * b2 - rb * rb;
            double E = rg * rb - rr * gb;
            double F = rr * gg - rg * rg;
            double det = rr * A + rg * Bc + rb * C;
            double ar = (vR * A + vG * Bc + vB * C) / det;
            double ag = (vR * Bc + vG * D + vB * E) / det;
            double ab = (vR * C + vG * E + vB * F) / det;
            aR[i] = (float) ar;
            aG[i] = (float) ag;
            aB[i] = (float) ab;
            bb[i] = (float) (mP[i] - ar * mR[i] - ag * mG[i] - ab * mB[i]);
        }

        float[] mAR = boxBlur(aR, w, h, radius);
        float[] mAG = boxBlur(aG, w, h, radius);
        float[] mAB = boxBlur(aB, w, h, radius);
        float[] mBB = boxBlur(bb, w, h, radius);
        float[] q = new float[n];
        for (int i = 0; i < n; i++) {
            float v = mAR[i] * R[i] + mAG[i] * G[i] + mAB[i] * B[i] + mBB[i];
            q[i] = v < 0f ? 0f : v > 1f ? 1f : v;
        }
        return q;
    }

    /**
     * Turns the guided alpha back into a snap-limited mask: re-steepen the
     * ramp (so edge-less boundaries stay tight instead of smearing over the
     * filter radius), then clamp inside the hard mask's eroded/dilated band
     * so the edge can move at most {@code band} px.
     */
    static float[] snap(float[] hard, float[] q, int w, int h, int band) {
        float[] inner = boxBlur(hard, w, h, band);
        float[] out = new float[hard.length];
        for (int i = 0; i < hard.length; i++) {
            double t = (q[i] - STEEPEN_LO) / (STEEPEN_HI - STEEPEN_LO);
            t = t < 0 ? 0 : t > 1 ? 1 : t;
            double s = t * t * (3 - 2 * t);
            if (inner[i] >= 0.9999f) s = 1;      // deep inside — always covered
            else if (inner[i] <= 0.0001f) s = 0; // beyond the band — never covered
            out[i] = (float) s;
        }
        return out;
    }

    /**
     * Separable box blur with the sampling window clamped to the image
     * (window average over the pixels that exist), so coverage at the border
     * is not diluted by imaginary pixels outside it.
     */
    static float[] boxBlur(float[] src, int w, int h, int r) {
        float[] tmp = new float[src.length];
        double[] pre = new double[Math.max(w, h) + 1];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) pre[x + 1] = pre[x] + src[row + x];
            for (int x = 0; x < w; x++) {
                int lo = Math.max(0, x - r);
                int hi = Math.min(w - 1, x + r);
                tmp[row + x] = (float) ((pre[hi + 1] - pre[lo]) / (hi - lo + 1));
            }
        }
        float[] out = new float[src.length];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) pre[y + 1] = pre[y] + tmp[y * w + x];
            for (int y = 0; y < h; y++) {
                int lo = Math.max(0, y - r);
                int hi = Math.min(h - 1, y + r);
                out[y * w + x] = (float) ((pre[hi + 1] - pre[lo]) / (hi - lo + 1));
            }
        }
        return out;
    }

    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] * b[i];
        return o;
    }

    private static BufferedImage resizeSmooth(BufferedImage src, int w, int h, int type) {
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static float[][] toRgbPlanes(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] R = new float[w * h];
        float[] G = new float[w * h];
        float[] B = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int i = y * w + x;
                R[i] = ((p >> 16) & 0xff) / 255f;
                G[i] = ((p >> 8) & 0xff) / 255f;
                B[i] = (p & 0xff) / 255f;
            }
        }
        return new float[][]{R, G, B};
    }

    /** Grayscale mask → binary coverage (0/1), thresholded at 50% so the
     *  bilinear downscale's edge ramp collapses back to a clean boundary. */
    private static float[] toBinaryCoverage(BufferedImage gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = gray.getRGB(x, y);
                int g = (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3;
                out[y * w + x] = g > 127 ? 1f : 0f;
            }
        }
        return out;
    }
}
