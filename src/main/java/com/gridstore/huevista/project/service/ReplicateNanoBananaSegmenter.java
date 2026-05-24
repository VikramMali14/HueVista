package com.gridstore.huevista.project.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls Google's Nano Banana (Gemini Image) family via the Replicate
 * platform — same models as the direct Google API, but reuses the
 * existing REPLICATE_API_TOKEN so the user doesn't need to manage a
 * separate Google Cloud / AI Studio key.
 *
 * Models on Replicate as of 2026:
 *   google/nano-banana-2      — Gemini 3.1 Flash Image (recommended default)
 *   google/nano-banana-pro    — Gemini 3 Pro Image (highest quality, ~3x cost)
 *   google/nano-banana        — Gemini 2.5 Flash Image (cheapest, older)
 *
 * Honest caveat: like the direct-Google variant in
 * {@link GeminiImageSegmenter}, this is image GENERATION, not pixel
 * extraction. Pixel alignment isn't guaranteed. Recent versions are
 * substantially better than older diffusion models but it's still worth
 * comparing this path's masks against the ADE20K and SAM paths on your
 * photo distribution.
 *
 * Configuration:
 *   replicate.nano-banana.enabled       — kill switch (default false)
 *   replicate.nano-banana.model         — owner/name (default google/nano-banana-2)
 *   replicate.nano-banana.model-version — pin a version hash for production
 *
 * Cost: ~$0.03-0.10 per mask depending on which Nano Banana variant.
 * One call per category = up to ~$0.30/upload on Pro, ~$0.10/upload
 * on Flash. User explicitly asked for quality over cost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicateNanoBananaSegmenter {

    private final RestTemplate restTemplate;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.nano-banana.model:google/nano-banana-2}")
    private String model;

    @Value("${replicate.nano-banana.model-version:}")
    private String modelVersion;

    @Value("${replicate.nano-banana.enabled:false}")
    private boolean enabled;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 90; // image gen can take 60-90s

    public boolean isConfigured() {
        return enabled
                && replicateApiToken != null && !replicateApiToken.isBlank()
                && model != null && !model.isBlank();
    }

    /**
     * Generates a binary mask image for one paintable surface category.
     * The model receives the original photo (via URL) and a prompt
     * describing exactly which surface to mask.
     *
     * @param imageUrl           publicly-fetchable URL of the source photo
     * @param surfaceDescription what to mask (e.g. "the main painted wall")
     * @return mask bytes (PNG/JPEG, white = surface) or Optional.empty()
     */
    public Optional<byte[]> generateMask(String imageUrl, String surfaceDescription) {
        if (!isConfigured()) {
            log.debug("Nano Banana (Replicate) not configured — skipping");
            return Optional.empty();
        }
        try {
            log.info("Nano Banana (Replicate) [{}]: requesting mask for '{}'", model, surfaceDescription);

            String prompt = buildMaskPrompt(surfaceDescription);

            // Nano Banana on Replicate expects:
            //   prompt:       the text instruction
            //   image_input:  list of source image URLs (for editing context)
            //   output_format optional, "png" preferred for masks
            Map<String, Object> input = Map.of(
                    "prompt", prompt,
                    "image_input", List.of(imageUrl),
                    "output_format", "png"
            );

            String predictionId = startPrediction(input);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("Nano Banana (Replicate) prediction timed out or failed");
                return Optional.empty();
            }

            String maskUrl = extractOutputUrl(result.get("output"));
            if (maskUrl == null) {
                log.warn("Nano Banana (Replicate) returned no output URL");
                return Optional.empty();
            }

            byte[] bytes = downloadBytes(maskUrl);
            log.info("Nano Banana (Replicate) generated mask for '{}': {} bytes", surfaceDescription, bytes.length);
            return Optional.of(bytes);

        } catch (Exception e) {
            log.warn("Nano Banana (Replicate) failed for '{}': {}", surfaceDescription, e.getMessage());
            return Optional.empty();
        }
    }

    private String buildMaskPrompt(String surfaceDescription) {
        return ("Generate a black-and-white binary segmentation MASK image. The mask must be the "
                + "same exact dimensions as the input photograph.\n\n"
                + "TARGET SURFACE: " + surfaceDescription + "\n\n"
                + "RULES:\n"
                + "- Pure WHITE (#FFFFFF) pixels for the target surface only.\n"
                + "- Pure BLACK (#000000) pixels for everything else: sky, ground, vegetation, "
                + "vehicles, doors, windows, stone, brick, tile, fixtures, decorations.\n"
                + "- Mask must be PIXEL-ALIGNED with the input photo — same resolution, same "
                + "object positions.\n"
                + "- No grey pixels, no anti-aliasing, no gradients, no text, no annotations.\n"
                + "- Output: the mask image only.\n");
    }

    private String startPrediction(Map<String, Object> input) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + replicateApiToken);

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
            log.debug("Nano Banana prediction started: id={}", id);
            return id;
        } catch (Exception e) {
            log.warn("Failed to start Nano Banana prediction: {}", e.getMessage());
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
                log.warn("Nano Banana prediction terminal status: {} error: {}",
                        status, body.get("error"));
                return null;
            }
        }
        return null;
    }

    /**
     * Nano Banana output on Replicate can be a single URL string, a list
     * of URLs, or a dict — depends on the model variant. Handle all three.
     */
    @SuppressWarnings("unchecked")
    private String extractOutputUrl(Object output) {
        if (output == null) return null;
        if (output instanceof String s && !s.isBlank()) return s;
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String s) return s;
            if (first instanceof Map<?, ?> m) {
                Object u = m.get("url");
                if (u instanceof String s2) return s2;
            }
        }
        if (output instanceof Map<?, ?> map) {
            for (String key : new String[]{"image", "output", "url"}) {
                Object v = map.get(key);
                if (v instanceof String s) return s;
            }
        }
        return null;
    }

    private byte[] downloadBytes(String url) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (body == null) throw new RuntimeException("Empty response downloading " + url);
        return body;
    }
}
