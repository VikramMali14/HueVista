package com.gridstore.huevista.project.service;

import javax.imageio.ImageIO;
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
    }
}
