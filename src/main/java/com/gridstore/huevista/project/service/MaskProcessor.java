package com.gridstore.huevista.project.service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Splits a binary segmentation mask into separately-paintable regions.
 *
 * The auto-segmenter (Grounded SAM) returns one mask covering all walls
 * smashed together. To let the user paint Main, Accent, and Other walls in
 * different colors, we need to break that single mask into its connected
 * components. Each blob becomes its own Region with its own mask PNG.
 *
 * 8-connectivity (including diagonals) so faint JPEG-compression gaps along
 * wall corners don't split one wall into two.
 */
final class MaskProcessor {

    private MaskProcessor() {}

    /** Pixels with combined grayscale value above this count as mask foreground. */
    private static final int FOREGROUND_THRESHOLD = 127;

    /**
     * Subtracts {@code backgroundBytes} from {@code foregroundBytes} in pixel
     * space — output = foreground AND NOT dilate(background, n). Used to
     * remove non-paintable surfaces (stone cladding, exposed brick, tile)
     * from a wall mask. The background mask is dilated by
     * {@code backgroundDilationIterations} before subtraction so we don't
     * leave a thin fringe along the boundary where grounded_sam's edge was
     * a pixel or two short of the real surface.
     *
     * If the two masks are different sizes, throws IOException — callers
     * should fall back to the un-subtracted foreground.
     */
    static byte[] subtract(byte[] foregroundBytes, byte[] backgroundBytes,
                           int backgroundDilationIterations) throws IOException {
        BufferedImage fg = ImageIO.read(new ByteArrayInputStream(foregroundBytes));
        BufferedImage bg = ImageIO.read(new ByteArrayInputStream(backgroundBytes));
        if (fg == null || bg == null) {
            throw new IOException("Could not decode mask for subtraction");
        }
        if (fg.getWidth() != bg.getWidth() || fg.getHeight() != bg.getHeight()) {
            throw new IOException(String.format(
                    "Mask size mismatch: fg=%dx%d, bg=%dx%d",
                    fg.getWidth(), fg.getHeight(), bg.getWidth(), bg.getHeight()));
        }
        int w = fg.getWidth(), h = fg.getHeight();

        boolean[] fgBin = thresholdToBinary(fg, w, h);
        boolean[] bgBin = thresholdToBinary(bg, w, h);
        for (int i = 0; i < backgroundDilationIterations; i++) {
            bgBin = dilate(bgBin, w, h);
        }

        boolean[] out = new boolean[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = fgBin[i] && !bgBin[i];
        }
        return encodeBinaryPng(out, w, h);
    }

    /**
     * Counts the foreground (white) pixels in a binary mask. Used by callers
     * to sanity-check a model's output — e.g. if the non-paintable mask
     * covers 90%+ of the frame, the detector misfired and we should skip
     * subtraction rather than wipe out the wall.
     */
    static int countForeground(byte[] maskBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(maskBytes));
        if (img == null) throw new IOException("Could not decode mask");
        int w = img.getWidth(), h = img.getHeight();
        boolean[] bin = thresholdToBinary(img, w, h);
        int n = 0;
        for (boolean b : bin) if (b) n++;
        return n;
    }

    /**
     * Overlays a list of masks on the original image with each one tinted a
     * different color and labeled with its index number (1, 2, 3, ...) at its
     * centroid. The output is the input image Claude sees for Set-of-Mark
     * classification — Claude only has to read the numbers and say which is
     * the main wall vs accent vs trim, instead of trying to output pixel
     * coordinates from scratch.
     *
     * Resizes the output to at most {@code maxDim} on its longest side so
     * Claude's input doesn't balloon (3-megapixel photos waste tokens).
     */
    static byte[] annotateComposite(byte[] originalBytes, List<byte[]> maskBytesList, int maxDim)
            throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalBytes));
        if (original == null) throw new IOException("Could not decode original image");

        int srcW = original.getWidth();
        int srcH = original.getHeight();
        double scale = Math.min(1.0, (double) maxDim / Math.max(srcW, srcH));
        int outW = (int) Math.round(srcW * scale);
        int outH = (int) Math.round(srcH * scale);

        BufferedImage composite = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, outW, outH, null);

        // Color palette — 12 distinct hues so adjacent masks don't blend.
        Color[] palette = {
                new Color(231, 76, 60), new Color(46, 204, 113), new Color(52, 152, 219),
                new Color(241, 196, 15), new Color(155, 89, 182), new Color(26, 188, 156),
                new Color(230, 126, 34), new Color(52, 73, 94), new Color(231, 76, 200),
                new Color(149, 165, 166), new Color(243, 156, 18), new Color(22, 160, 133)
        };

        // Larger labels — earlier 18px font with outW/30 formula was unreadable
        // for Claude on portrait images. Bumped so 1568px composite gets ~78px
        // labels and a 600px one still gets 36px.
        int fontSize = Math.max(36, Math.min(96, outW / 20));
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < maskBytesList.size(); i++) {
            BufferedImage maskRaw = ImageIO.read(new ByteArrayInputStream(maskBytesList.get(i)));
            if (maskRaw == null) continue;
            // Resize each mask to the composite dimensions.
            BufferedImage mask = resizeNearest(maskRaw, outW, outH);

            Color tint = palette[i % palette.length];
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.40f));
            int rgb = (tint.getRed() << 16) | (tint.getGreen() << 8) | tint.getBlue();

            // Centroid accumulators
            long cxSum = 0, cySum = 0;
            int count = 0;

            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    int p = mask.getRGB(x, y);
                    int gray = (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3;
                    if (gray > FOREGROUND_THRESHOLD) {
                        composite.setRGB(x, y, blend(composite.getRGB(x, y), rgb, 0.40f));
                        cxSum += x;
                        cySum += y;
                        count++;
                    }
                }
            }
            if (count == 0) continue;

            int cx = (int) (cxSum / count);
            int cy = (int) (cySum / count);
            String label = String.valueOf(i + 1);
            int labelW = fm.stringWidth(label);
            int labelH = fm.getAscent();
            int padding = Math.max(4, fontSize / 4);
            int boxX = cx - labelW / 2 - padding;
            int boxY = cy - labelH / 2 - padding;
            int boxW = labelW + padding * 2;
            int boxH = labelH + padding * 2;

            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(Color.WHITE);
            g.fillRect(boxX, boxY, boxW, boxH);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(boxX, boxY, boxW, boxH);
            g.drawString(label, cx - labelW / 2, cy + labelH / 2 - 2);
        }

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(composite, "jpg", out)) {
            throw new IOException("JPEG encoder not available");
        }
        return out.toByteArray();
    }

    private static BufferedImage resizeNearest(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static int blend(int base, int over, float alpha) {
        int br = (base >> 16) & 0xff, bg = (base >> 8) & 0xff, bb = base & 0xff;
        int or = (over >> 16) & 0xff, og = (over >> 8) & 0xff, ob = over & 0xff;
        int r = (int) (br * (1 - alpha) + or * alpha);
        int gr = (int) (bg * (1 - alpha) + og * alpha);
        int b = (int) (bb * (1 - alpha) + ob * alpha);
        return (r << 16) | (gr << 8) | b;
    }

    /**
     * Quick geometric stats on a binary mask — used by the segmenter to
     * filter out sky/ground/background masks before they reach Claude.
     * Computed on whatever input resolution is provided; pass a downsampled
     * copy if speed matters.
     *
     * "touches top/bottom/left/right" uses a {@code edgeTolerancePx}-wide
     * band rather than the exact 1-pixel edge — a sky or ground mask
     * doesn't always reach the literal pixel border but is still clearly
     * background.
     */
    static MaskStats stats(BufferedImage mask, int edgeTolerancePx) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int tol = Math.max(1, edgeTolerancePx);
        int foreground = 0;
        boolean touchesTop = false, touchesBottom = false, touchesLeft = false, touchesRight = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = mask.getRGB(x, y);
                int gray = (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3;
                if (gray > FOREGROUND_THRESHOLD) {
                    foreground++;
                    if (y < tol) touchesTop = true;
                    else if (y >= h - tol) touchesBottom = true;
                    if (x < tol) touchesLeft = true;
                    else if (x >= w - tol) touchesRight = true;
                }
            }
        }
        return new MaskStats(foreground, w * h, touchesTop, touchesBottom, touchesLeft, touchesRight);
    }

    /** Convenience: 1-pixel tolerance for callers that don't care. */
    static MaskStats stats(BufferedImage mask) {
        return stats(mask, 1);
    }

    record MaskStats(int foregroundPixels, int totalPixels,
                     boolean touchesTop, boolean touchesBottom,
                     boolean touchesLeft, boolean touchesRight) {
        double foregroundFraction() {
            return totalPixels == 0 ? 0.0 : (double) foregroundPixels / totalPixels;
        }
    }

    /**
     * Computes the mean RGB color of the original photo's pixels that fall
     * inside the given binary mask. Returns null if the mask is empty.
     *
     * Caller is expected to pre-resize both inputs to the same small
     * resolution (e.g. 512px longest side) — this method does no resizing
     * itself, so full-res inputs would be wasteful. {@link #downsampleJpeg}
     * is provided for that purpose.
     */
    static int[] meanColor(BufferedImage original, BufferedImage mask) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (mask.getWidth() != w || mask.getHeight() != h) {
            mask = resizeNearest(mask, w, h);
        }
        long sumR = 0, sumG = 0, sumB = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int mp = mask.getRGB(x, y);
                int gray = (((mp >> 16) & 0xff) + ((mp >> 8) & 0xff) + (mp & 0xff)) / 3;
                if (gray > FOREGROUND_THRESHOLD) {
                    int op = original.getRGB(x, y);
                    sumR += (op >> 16) & 0xff;
                    sumG += (op >> 8) & 0xff;
                    sumB += op & 0xff;
                    count++;
                }
            }
        }
        if (count == 0) return null;
        return new int[]{(int) (sumR / count), (int) (sumG / count), (int) (sumB / count)};
    }

    /**
     * Mean RGB color across the union of several mask images. Used to
     * compute the "average wall color" from the masks Claude already
     * picked, which we then use to find OTHER masks that match that color.
     */
    static int[] meanColorAcrossMasks(BufferedImage original, List<BufferedImage> masks) {
        if (masks == null || masks.isEmpty()) return null;
        int w = original.getWidth();
        int h = original.getHeight();
        boolean[] union = new boolean[w * h];
        for (BufferedImage mask : masks) {
            if (mask == null) continue;
            BufferedImage m = (mask.getWidth() != w || mask.getHeight() != h)
                    ? resizeNearest(mask, w, h) : mask;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int mp = m.getRGB(x, y);
                    int gray = (((mp >> 16) & 0xff) + ((mp >> 8) & 0xff) + (mp & 0xff)) / 3;
                    if (gray > FOREGROUND_THRESHOLD) union[y * w + x] = true;
                }
            }
        }
        long sumR = 0, sumG = 0, sumB = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (union[y * w + x]) {
                    int op = original.getRGB(x, y);
                    sumR += (op >> 16) & 0xff;
                    sumG += (op >> 8) & 0xff;
                    sumB += op & 0xff;
                    count++;
                }
            }
        }
        if (count == 0) return null;
        return new int[]{(int) (sumR / count), (int) (sumG / count), (int) (sumB / count)};
    }

    /** Plain RGB Euclidean distance — good enough for "are these the same color" decisions. */
    static double colorDistance(int[] c1, int[] c2) {
        int dr = c1[0] - c2[0];
        int dg = c1[1] - c2[1];
        int db = c1[2] - c2[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * Decodes JPEG/PNG bytes to a BufferedImage at the original resolution.
     */
    static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("Could not decode image");
        return img;
    }

    /**
     * Returns a downsampled copy of an image with the longest side capped at
     * {@code maxDim}. Used to make color analysis tractable — computing mean
     * RGB on a 7-megapixel photo across 40 masks is too slow; a 512px copy
     * gives the same answer 50× faster.
     */
    static BufferedImage downsample(BufferedImage src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min(1.0, (double) maxDim / Math.max(w, h));
        if (scale >= 1.0) return src;
        int outW = (int) Math.round(w * scale);
        int outH = (int) Math.round(h * scale);
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, outW, outH, null);
        g.dispose();
        return out;
    }

    /**
     * Pixel-wise union of multiple binary masks: output pixel is white if any
     * input mask is white at that location. All masks must share dimensions.
     * Used after Claude's Set-of-Mark classification to combine, e.g., mask #4
     * and mask #7 into a single MAIN_WALL region.
     */
    static byte[] unionMasks(List<byte[]> maskBytesList) throws IOException {
        if (maskBytesList.isEmpty()) {
            throw new IOException("unionMasks: empty input");
        }
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(maskBytesList.get(0)));
        if (first == null) throw new IOException("Could not decode mask 0 for union");
        int w = first.getWidth(), h = first.getHeight();

        boolean[] acc = thresholdToBinary(first, w, h);
        for (int i = 1; i < maskBytesList.size(); i++) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(maskBytesList.get(i)));
            if (img == null) continue;
            if (img.getWidth() != w || img.getHeight() != h) {
                // Resize to match
                img = resizeNearest(img, w, h);
            }
            boolean[] bin = thresholdToBinary(img, w, h);
            for (int j = 0; j < acc.length; j++) {
                if (bin[j]) acc[j] = true;
            }
        }
        return encodeBinaryPng(acc, w, h);
    }

    /**
     * Cleans up a binary mask using morphological close-then-open with a 3×3
     * structuring element. Close (dilate→erode) fills small holes — paint-through
     * gaps where the model dropped a few wall pixels around light switches or
     * picture frames. Open (erode→dilate) removes speckle noise — stray "wall"
     * pixels on the floor, sky, or sofa. Both operations preserve overall wall
     * shape. Returns a re-encoded PNG.
     */
    static byte[] morphClean(byte[] maskBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(maskBytes));
        if (img == null) {
            throw new IOException("Could not decode mask image for morphological cleanup");
        }
        int width = img.getWidth();
        int height = img.getHeight();

        boolean[] binary = thresholdToBinary(img, width, height);
        boolean[] closed = erode(dilate(binary, width, height), width, height);
        boolean[] cleaned = dilate(erode(closed, width, height), width, height);
        return encodeBinaryPng(cleaned, width, height);
    }

    private static boolean[] thresholdToBinary(BufferedImage img, int w, int h) {
        boolean[] bin = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int gray = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
                bin[y * w + x] = gray > FOREGROUND_THRESHOLD;
            }
        }
        return bin;
    }

    /** 3×3 dilation: a pixel becomes white if itself or any 8-neighbor is white. */
    private static boolean[] dilate(boolean[] in, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (in[idx]) { out[idx] = true; continue; }
                boolean any = false;
                outer:
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        if (in[ny * w + nx]) { any = true; break outer; }
                    }
                }
                out[idx] = any;
            }
        }
        return out;
    }

    /** 3×3 erosion: a pixel stays white only if every 8-neighbor (and the pixel) is white. */
    private static boolean[] erode(boolean[] in, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!in[idx]) continue;
                boolean all = true;
                outer:
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) { all = false; break; }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) { all = false; break outer; }
                        if (!in[ny * w + nx]) { all = false; break outer; }
                    }
                }
                out[idx] = all;
            }
        }
        return out;
    }

    private static byte[] encodeBinaryPng(boolean[] bin, int w, int h) throws IOException {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < bin.length; i++) {
            data[i] = bin[i] ? (byte) 0xFF : 0;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", baos)) {
            throw new IOException("PNG encoder not available");
        }
        return baos.toByteArray();
    }

    /**
     * Decodes mask bytes (PNG or JPEG), thresholds to binary, and runs
     * connected-component labeling. Components below `minPixelArea` are
     * dropped as noise. Returned list is sorted by area descending.
     */
    static MaskAnalysis analyze(byte[] imageBytes, int minPixelArea) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (img == null) {
            throw new IOException("Could not decode mask image (unsupported format or corrupt data)");
        }
        int width = img.getWidth();
        int height = img.getHeight();

        boolean[] binary = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int gray = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
                binary[y * width + x] = gray > FOREGROUND_THRESHOLD;
            }
        }

        // short labels: supports up to 32767 components — far more than we'd
        // ever keep, and saves memory vs int[] on high-res masks.
        short[] labels = new short[width * height];
        List<Component> components = new ArrayList<>();
        short nextLabel = 1;

        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (!binary[idx] || labels[idx] != 0) continue;

                short label = nextLabel++;
                if (nextLabel < 0) {
                    // overflow guard — won't happen on realistic masks but
                    // keep the algorithm safe
                    throw new IOException("Too many connected components");
                }
                Component component = new Component();
                component.label = label;
                component.minX = x;
                component.minY = y;
                component.maxX = x;
                component.maxY = y;

                Deque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[]{x, y});
                labels[idx] = label;

                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    int px = p[0], py = p[1];
                    component.area++;
                    if (px < component.minX) component.minX = px;
                    if (px > component.maxX) component.maxX = px;
                    if (py < component.minY) component.minY = py;
                    if (py > component.maxY) component.maxY = py;
                    long sx = component.centroidSumX + px;
                    long sy = component.centroidSumY + py;
                    component.centroidSumX = sx;
                    component.centroidSumY = sy;

                    for (int d = 0; d < 8; d++) {
                        int nx = px + dx[d];
                        int ny = py + dy[d];
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                        int nIdx = ny * width + nx;
                        if (binary[nIdx] && labels[nIdx] == 0) {
                            labels[nIdx] = label;
                            queue.add(new int[]{nx, ny});
                        }
                    }
                }

                if (component.area >= minPixelArea) {
                    components.add(component);
                }
            }
        }

        components.sort(Comparator.comparingInt((Component c) -> c.area).reversed());
        return new MaskAnalysis(width, height, labels, components);
    }

    /**
     * Encodes a single component as a grayscale PNG — white where the
     * component is, black elsewhere — at the same dimensions as the source.
     */
    static byte[] encodeComponentPng(MaskAnalysis analysis, Component component) throws IOException {
        BufferedImage out = new BufferedImage(
                analysis.width, analysis.height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        short target = component.label;
        for (int i = 0; i < analysis.labels.length; i++) {
            data[i] = (analysis.labels[i] == target) ? (byte) 0xFF : 0;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", baos)) {
            throw new IOException("PNG encoder not available");
        }
        return baos.toByteArray();
    }

    /**
     * Encodes every retained component (those above the area threshold) as a
     * single combined PNG. Used for the trim mask where every detected piece
     * — window frames, door frames, baseboards — collectively becomes one
     * paintable region.
     */
    static byte[] encodeAllComponentsPng(MaskAnalysis analysis) throws IOException {
        BufferedImage out = new BufferedImage(
                analysis.width, analysis.height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();

        boolean[] keep = new boolean[Short.MAX_VALUE];
        for (Component c : analysis.components) keep[c.label] = true;

        for (int i = 0; i < analysis.labels.length; i++) {
            short l = analysis.labels[i];
            data[i] = (l > 0 && keep[l]) ? (byte) 0xFF : 0;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", baos)) {
            throw new IOException("PNG encoder not available");
        }
        return baos.toByteArray();
    }

    static final class Component {
        short label;
        int area;
        int minX, minY, maxX, maxY;
        long centroidSumX;
        long centroidSumY;

        int centroidX() { return (int) (centroidSumX / Math.max(1, area)); }
        int centroidY() { return (int) (centroidSumY / Math.max(1, area)); }
    }

    static final class MaskAnalysis {
        final int width;
        final int height;
        final short[] labels;
        final List<Component> components;

        MaskAnalysis(int width, int height, short[] labels, List<Component> components) {
            this.width = width;
            this.height = height;
            this.labels = labels;
            this.components = components;
        }

        /**
         * Total foreground pixels across ALL labels (including components
         * that were dropped for being too small). Useful for logging when
         * components.isEmpty() — distinguishes "model returned blank mask"
         * (0 px) from "model found something but the threshold rejected it"
         * (>0 px).
         */
        int totalForegroundPixels() {
            int sum = 0;
            for (short l : labels) if (l > 0) sum++;
            return sum;
        }
    }
}
