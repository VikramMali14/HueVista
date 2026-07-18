package com.gridstore.huevista.project.service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pixel toolbox for the colour-coded mask pipeline: splits the model's
 * RED/GREEN/BLUE/BLACK image into per-category binary masks
 * ({@link #splitColorCodedMask}), smooth-resizes them to the canvas
 * resolution ({@link #resizeBinarySmooth}) and repairs the occasional
 * inverted SAM point mask ({@link #ensureWhiteForeground}). The masks are
 * otherwise stored exactly as the model produced them — no post-processing.
 *
 * 8-connectivity (including diagonals) wherever blobs are traced, so faint
 * JPEG-compression gaps along wall corners don't split one wall into two.
 */
final class MaskProcessor {

    private MaskProcessor() {}

    /** Pixels with combined grayscale value above this count as mask foreground. */
    private static final int FOREGROUND_THRESHOLD = 127;

    /**
     * Counts the foreground (white) pixels in a binary mask. Used by callers
     * to sanity-check a model's output against the per-category size
     * thresholds — a near-empty mask means the model couldn't find that
     * surface and the category is skipped instead of persisting noise.
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
     * Decodes JPEG/PNG bytes to a BufferedImage at the original resolution.
     */
    static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("Could not decode image");
        return img;
    }

    /**
     * Returns a downsampled copy of an image with the longest side capped at
     * {@code maxDim}. Used to bring canvases down to the stored-mask
     * resolution the region masks are sized off.
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

    static boolean[] thresholdToBinary(BufferedImage img, int w, int h) {
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

    static byte[] encodeBinaryPng(boolean[] bin, int w, int h) throws IOException {
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
     * Splits a color-coded segmentation mask (produced by a single
     * Nano Banana / Gemini call) into per-category binary masks.
     *
     * Pixel classification (distinct-hue scheme — high chroma separates reliably):
     *   - RED-dominant   (R ≥ G+40 AND R ≥ B+40 AND R ≥ 100)          → "main"
     *   - GREEN-dominant (G ≥ R+40 AND G ≥ B+40 AND G ≥ 100)          → "accent"
     *   - BLUE-dominant  (B ≥ R+40 AND B ≥ G+40 AND B ≥ 100)          → "trim"
     *   - near-WHITE     (all channels ≥ 170, spread ≤ 50)            → salvage bucket
     *   - everything else (black, ambiguous, anti-aliased edges)      → unassigned
     *
     * WHITE salvage: the model sometimes disobeys the four-colour instruction
     * and leaves the accent / feature volume WHITE (typically because that
     * surface is already white in the cleaned photo, so it "paints" it white
     * again). Those pixels used to be dropped, collapsing the output to just
     * main + trim — two masks where the user expects three (main, highlight,
     * border). When no usable GREEN accent exists, a large near-white area is
     * therefore adopted as the accent mask instead of being thrown away. A
     * genuine green accent always wins; the white bucket is only the fallback.
     *
     * <p>Before adoption the white bucket is run through
     * {@link #filterWhiteSalvage}: an accent wall is ONE surface, but the
     * bucket is a colour threshold over the whole frame and also catches
     * clouds, an overcast sky, bright vehicles and reflections. Only the
     * largest connected blob survives, and (when {@code skyFilter} is set —
     * exterior scenes) blobs touching the very top of the frame are rejected
     * outright, so an off-spec washed-out sky can never become the paintable
     * "accent wall".
     *
     * Returns a map keyed by "main", "accent", "trim". Categories with fewer
     * than {@code minPixels} foreground pixels are omitted from the map so
     * callers can skip saving empty regions.
     */
    static java.util.Map<String, byte[]> splitColorCodedMask(byte[] colorMaskBytes, int minPixels)
            throws IOException {
        return splitColorCodedMask(colorMaskBytes, minPixels, true);
    }

    /**
     * @param skyFilter when true (exterior/unknown scenes), white-salvage blobs
     *                  touching the top edge of the frame are rejected as sky;
     *                  interiors pass false — a full-bleed wall in a photo
     *                  cropped above the ceiling legitimately touches the top.
     */
    static java.util.Map<String, byte[]> splitColorCodedMask(byte[] colorMaskBytes, int minPixels,
                                                             boolean skyFilter)
            throws IOException {
        BufferedImage img = decode(colorMaskBytes);
        int w = img.getWidth();
        int h = img.getHeight();

        boolean[] mainBin = new boolean[w * h];
        boolean[] trimBin = new boolean[w * h];
        boolean[] accentBin = new boolean[w * h];
        boolean[] whiteBin = new boolean[w * h];
        int mainCount = 0, trimCount = 0, accentCount = 0, whiteCount = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int idx = y * w + x;

                // Distinct-hue scheme (pushed apart for reliable separation):
                //   RED-dominant   → main wall
                //   GREEN-dominant → accent wall
                //   BLUE-dominant  → trim
                if (r >= g + 40 && r >= b + 40 && r >= 100) {
                    mainBin[idx] = true;
                    mainCount++;
                    continue;
                }
                if (g >= r + 40 && g >= b + 40 && g >= 100) {
                    accentBin[idx] = true;
                    accentCount++;
                    continue;
                }
                if (b >= r + 40 && b >= g + 40 && b >= 100) {
                    trimBin[idx] = true;
                    trimCount++;
                    continue;
                }
                // Near-white (bright + low chroma): an off-spec colour the model
                // used for a surface it should have painted green. Collected
                // separately as the accent fallback below.
                int min = Math.min(r, Math.min(g, b));
                int max = Math.max(r, Math.max(g, b));
                if (min >= 170 && max - min <= 50) {
                    whiteBin[idx] = true;
                    whiteCount++;
                    continue;
                }
                // Anti-aliased / JPEG-softened pixels along a border BETWEEN two
                // colour blocks read as a mix (magenta on a red|blue border,
                // yellow on red|green): bright and clearly chromatic, but
                // failing every dominance test above. Dropping them (the old
                // behaviour) left an unassigned ribbon along every category
                // border, which rendered as an unpainted white seam between
                // regions. Adopt them into the strongest channel's category
                // instead. Near-black stays unassigned (the model's
                // "everything else"), and greys (railing silver, ambiguous
                // noise) keep failing the chroma requirement.
                if (max >= 100 && max - min >= 40) {
                    if (r >= g && r >= b) {
                        mainBin[idx] = true;
                        mainCount++;
                    } else if (g >= b) {
                        accentBin[idx] = true;
                        accentCount++;
                    } else {
                        trimBin[idx] = true;
                        trimCount++;
                    }
                }
                // else: black or ambiguous — leave unassigned.
            }
        }

        // Prefer the green accent; adopt the white bucket only when green is
        // missing or too small to be a real wall — and even then only its
        // single plausible-wall blob (largest component, sky rejected).
        if (accentCount < minPixels && whiteCount >= minPixels) {
            boolean[] salvaged = filterWhiteSalvage(whiteBin, w, h, skyFilter);
            int salvagedCount = 0;
            for (boolean b : salvaged) if (b) salvagedCount++;
            if (salvagedCount >= minPixels) {
                accentBin = salvaged;
                accentCount = salvagedCount;
            }
        }

        java.util.Map<String, byte[]> out = new java.util.HashMap<>();
        if (mainCount >= minPixels) out.put("main", encodeBinaryPng(mainBin, w, h));
        if (trimCount >= minPixels) out.put("trim", encodeBinaryPng(trimBin, w, h));
        if (accentCount >= minPixels) out.put("accent", encodeBinaryPng(accentBin, w, h));
        return out;
    }

    /**
     * Reduces a white-salvage bucket to the one blob that can plausibly be THE
     * accent wall. Keeps only the largest 8-connected component; when
     * {@code excludeTopTouching} is set, components reaching the top edge band
     * of the frame (sky always does on an exterior photo — a wall below the
     * roofline never does) are discarded before choosing. Returns an all-false
     * array when nothing qualifies — the caller then simply skips the salvage,
     * which beats shipping a paintable "accent wall" that is actually the sky.
     */
    static boolean[] filterWhiteSalvage(boolean[] bin, int w, int h, boolean excludeTopTouching) {
        int topBand = Math.max(1, h / 100);
        int[] labels = new int[w * h];
        int bestLabel = 0;
        int bestArea = 0;
        int nextLabel = 1;

        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        Deque<int[]> queue = new ArrayDeque<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!bin[idx] || labels[idx] != 0) continue;

                int label = nextLabel++;
                int area = 0;
                boolean touchesTop = false;
                labels[idx] = label;
                queue.add(new int[]{x, y});
                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    area++;
                    if (p[1] < topBand) touchesTop = true;
                    for (int d = 0; d < 8; d++) {
                        int nx = p[0] + dx[d];
                        int ny = p[1] + dy[d];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int nIdx = ny * w + nx;
                        if (bin[nIdx] && labels[nIdx] == 0) {
                            labels[nIdx] = label;
                            queue.add(new int[]{nx, ny});
                        }
                    }
                }

                if (excludeTopTouching && touchesTop) continue;
                if (area > bestArea) {
                    bestArea = area;
                    bestLabel = label;
                }
            }
        }

        boolean[] out = new boolean[w * h];
        if (bestLabel != 0) {
            for (int i = 0; i < out.length; i++) out[i] = labels[i] == bestLabel;
        }
        return out;
    }

    /**
     * Resizes a binary mask to {@code w}×{@code h} with BILINEAR interpolation
     * and re-thresholds the result. Interpolating the 0/255 edge and cutting it
     * at 50% grey lands the new boundary between the source pixels
     * (half-source-pixel accuracy), so a ~1K model mask upscaled to the canvas
     * resolution gets a smooth, straight edge instead of the enlarged staircase
     * blocks that nearest-neighbour scaling produces.
     */
    static byte[] resizeBinarySmooth(byte[] maskBytes, int w, int h) throws IOException {
        BufferedImage src = decode(maskBytes);
        if (src.getWidth() == w && src.getHeight() == h) return maskBytes;
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return encodeBinaryPng(thresholdToBinary(gray, w, h), w, h);
    }

    /**
     * Detects and corrects inverted masks where the segmented region is
     * black and the background is white. SAM 2 point mode on Replicate
     * sometimes returns masks in this inverted form. We detect inversion by
     * checking if black pixels dominate the image (>60%).
     */
    static byte[] ensureWhiteForeground(byte[] maskBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(maskBytes));
        if (img == null) throw new IOException("Could not decode mask");
        int w = img.getWidth(), h = img.getHeight();
        boolean[] bin = thresholdToBinary(img, w, h);

        int black = 0, white = 0;
        for (boolean b : bin) {
            if (b) white++;
            else black++;
        }
        // If black dominates, the mask is inverted (black = foreground).
        // Invert it so white = foreground.
        if (black > white) {
            for (int i = 0; i < bin.length; i++) {
                bin[i] = !bin[i];
            }
            return encodeBinaryPng(bin, w, h);
        }
        return maskBytes;
    }

}
