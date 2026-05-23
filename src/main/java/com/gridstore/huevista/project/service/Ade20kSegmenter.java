package com.gridstore.huevista.project.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Calls a Replicate-hosted ADE20K semantic-segmentation model
 * (Mask2Former / OneFormer / SegFormer family) and returns per-class
 * pixel masks. ADE20K has 150 scene classes including "wall", "door",
 * "windowpane", "sky", "ceiling", etc. — exactly what a paint
 * visualization app needs.
 *
 * This is the architectural escape hatch from SAM-based wall detection.
 * SAM segments objects; ADE20K models segment scenes, classifying every
 * pixel by what it IS. The "wall" class gives us the paint surface
 * directly with no Claude-in-the-loop guesswork.
 *
 * Returns Optional.empty() when:
 *   - the model isn't configured
 *   - the Replicate call fails or times out
 *   - the output can't be parsed
 * The caller should fall back to the SAM-based pipeline in those cases.
 *
 * Configuration (application.properties / env):
 *   replicate.ade20k.model         — owner/name (e.g. lucataco/mask2former-ade20k-semantic)
 *   replicate.ade20k.model-version — version hash, REQUIRED in production
 *                                    (third-party Replicate models only
 *                                     resolve via /predictions with a hash)
 *   replicate.ade20k.enabled       — toggle (default true if model configured)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ade20kSegmenter {

    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.ade20k.model:}")
    private String model;

    @Value("${replicate.ade20k.model-version:}")
    private String modelVersion;

    @Value("${replicate.ade20k.enabled:true}")
    private boolean enabled;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60;

    /**
     * Returns true if the segmenter is configured to make Replicate calls.
     * Callers should check this before invoking and fall through if false.
     */
    public boolean isConfigured() {
        return enabled
                && replicateApiToken != null && !replicateApiToken.isBlank()
                && model != null && !model.isBlank();
    }

    /**
     * Segments the photo and returns per-class binary mask PNGs keyed by
     * ADE20K class ID. Empty when not configured or when the model fails.
     */
    public Optional<Ade20kResult> segment(String imageUrl) {
        if (!isConfigured()) {
            log.debug("Ade20k segmenter not configured; skipping");
            return Optional.empty();
        }
        try {
            log.info("Starting ADE20K semantic segmentation: model={} url={}", model, imageUrl);

            String predictionId = startPrediction(imageUrl);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("ADE20K prediction timed out or failed");
                return Optional.empty();
            }

            Object output = result.get("output");
            Ade20kResult parsed = parseOutput(output);
            if (parsed == null || parsed.perClassMasks() == null || parsed.perClassMasks().isEmpty()) {
                log.warn("ADE20K returned no usable mask data");
                return Optional.empty();
            }

            log.info("ADE20K segmentation produced masks for {} classes: {}",
                    parsed.perClassMasks().size(), parsed.perClassMasks().keySet());
            return Optional.of(parsed);

        } catch (Exception e) {
            log.warn("ADE20K segmentation failed, falling back: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String startPrediction(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + replicateApiToken);

            Map<String, Object> input = Map.of("image", imageUrl);
            boolean hasPinnedVersion = modelVersion != null && !modelVersion.isBlank();
            Map<String, Object> body = hasPinnedVersion
                    ? Map.of("version", modelVersion, "input", input)
                    : Map.of("input", input);
            String endpoint = hasPinnedVersion
                    ? REPLICATE_BASE + "/predictions"
                    : REPLICATE_BASE + "/models/" + model + "/predictions";

            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            String id = (String) response.getBody().get("id");
            log.info("ADE20K prediction started: id={}", id);
            return id;
        } catch (Exception e) {
            log.warn("Failed to start ADE20K prediction: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilDone(String predictionId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + replicateApiToken);
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            String status = (String) body.get("status");
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("ADE20K prediction terminal status: {}", status);
                return null;
            }
        }
        return null;
    }

    /**
     * Parses Replicate output into per-class masks. Different Mask2Former /
     * OneFormer wrappers return output in different shapes:
     *
     *   Shape A: { class_name: url, ... }   — JSON map per class
     *   Shape B: { "labels": [{"label":"wall","mask":"url"}, ...] }
     *   Shape C: a single labeled PNG URL where pixel values = class IDs
     *   Shape D: a list of [{"label_id":0,"label":"wall","mask":"url"}, ...]
     *
     * We try each shape in order. If none match we log the output structure
     * so the user can adapt this method to their chosen model.
     */
    private Ade20kResult parseOutput(Object output) {
        if (output == null) return null;
        Map<Integer, byte[]> masks = new HashMap<>();

        try {
            // Shape A / D: list of detections with labels
            if (output instanceof List<?> list) {
                int w = 0, h = 0;
                for (Object item : list) {
                    if (item instanceof Map<?, ?> det) {
                        Integer classId = extractClassId(det);
                        String maskUrl = extractMaskUrl(det);
                        if (classId == null || maskUrl == null) continue;
                        byte[] bytes = downloadAndDecodeAsBinaryPng(maskUrl);
                        if (bytes == null) continue;
                        masks.put(classId, bytes);
                        int[] dims = readDims(bytes);
                        if (dims != null) { w = dims[0]; h = dims[1]; }
                    }
                }
                if (!masks.isEmpty()) return new Ade20kResult(masks, w, h);
            }

            // Shape B: { "labels": [...] } or { "masks": {...} }
            if (output instanceof Map<?, ?> map) {
                Object labelsNode = map.get("labels");
                if (labelsNode == null) labelsNode = map.get("predictions");
                if (labelsNode instanceof List<?> labels) {
                    return parseOutput(labels);
                }
                Object masksMap = map.get("masks");
                if (masksMap instanceof Map<?, ?> mm) {
                    int w = 0, h = 0;
                    for (Map.Entry<?, ?> e : mm.entrySet()) {
                        Integer classId = classIdFromKey(String.valueOf(e.getKey()));
                        if (classId == null) continue;
                        Object v = e.getValue();
                        if (!(v instanceof String url)) continue;
                        byte[] bytes = downloadAndDecodeAsBinaryPng(url);
                        if (bytes == null) continue;
                        masks.put(classId, bytes);
                        int[] dims = readDims(bytes);
                        if (dims != null) { w = dims[0]; h = dims[1]; }
                    }
                    if (!masks.isEmpty()) return new Ade20kResult(masks, w, h);
                }

                // Shape C: single label-encoded PNG URL
                Object segUrl = map.get("segmentation");
                if (segUrl == null) segUrl = map.get("output");
                if (segUrl == null) segUrl = map.get("mask");
                if (segUrl instanceof String url) {
                    return parseLabeledPng(url);
                }
            }

            // Shape C alone: just a URL string
            if (output instanceof String url) {
                return parseLabeledPng(url);
            }

            log.warn("ADE20K output had unknown shape: {}", output.getClass().getSimpleName());
            try {
                log.warn("ADE20K output content: {}", objectMapper.writeValueAsString(output));
            } catch (Exception ignored) {}
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse ADE20K output: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decodes a label-encoded PNG where each pixel's value is the ADE20K
     * class ID (8-bit grayscale or palette encoding). Splits into per-class
     * binary masks.
     */
    private Ade20kResult parseLabeledPng(String url) {
        try {
            byte[] bytes = downloadBytes(url);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            int w = img.getWidth();
            int h = img.getHeight();
            // Histogram of class IDs
            Map<Integer, boolean[]> perClass = new HashMap<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    // For grayscale-encoded labels, the class id is the
                    // low byte. For palette PNGs, the index lives in the
                    // alpha channel slot post-conversion to RGB. Use the
                    // low 8 bits as the canonical class id.
                    int classId = rgb & 0xff;
                    if (classId >= 150) continue;
                    boolean[] bin = perClass.computeIfAbsent(classId, k -> new boolean[w * h]);
                    bin[y * w + x] = true;
                }
            }
            Map<Integer, byte[]> masks = new HashMap<>();
            for (Map.Entry<Integer, boolean[]> e : perClass.entrySet()) {
                byte[] png = encodeBinaryPng(e.getValue(), w, h);
                if (png != null) masks.put(e.getKey(), png);
            }
            return new Ade20kResult(masks, w, h);
        } catch (Exception e) {
            log.warn("Failed to parse labeled PNG: {}", e.getMessage());
            return null;
        }
    }

    private Integer extractClassId(Map<?, ?> det) {
        Object id = det.get("class_id");
        if (id == null) id = det.get("label_id");
        if (id == null) id = det.get("class");
        if (id instanceof Number n) return n.intValue();
        // Fall back to label-name → id lookup
        Object label = det.get("label");
        if (label == null) label = det.get("name");
        if (label instanceof String s) return classIdFromKey(s);
        return null;
    }

    private String extractMaskUrl(Map<?, ?> det) {
        Object m = det.get("mask");
        if (m == null) m = det.get("mask_url");
        if (m == null) m = det.get("url");
        return (m instanceof String s) ? s : null;
    }

    private static Integer classIdFromKey(String key) {
        // Try numeric first
        try { return Integer.parseInt(key.trim()); } catch (NumberFormatException ignored) {}
        // Else look up by canonical ADE20K name
        return Ade20kClassNames.idFor(key);
    }

    private byte[] downloadBytes(String url) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (body == null) throw new RuntimeException("Empty response from " + url);
        return body;
    }

    private byte[] downloadAndDecodeAsBinaryPng(String url) {
        try {
            byte[] bytes = downloadBytes(url);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            boolean[] bin = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int gray = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
                    bin[y * w + x] = gray > 127;
                }
            }
            return encodeBinaryPng(bin, w, h);
        } catch (Exception e) {
            return null;
        }
    }

    private int[] readDims(byte[] pngBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return null;
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] encodeBinaryPng(boolean[] bin, int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            byte[] data = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < bin.length; i++) {
                data[i] = bin[i] ? (byte) 0xFF : 0;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
