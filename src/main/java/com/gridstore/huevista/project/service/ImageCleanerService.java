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
 * Calls Replicate's Nano Banana (Gemini Image) family to produce a
 * "cleaned" version of the user's house photo — wires, bushes, parked
 * cars, garbage, hanging laundry, and other clutter removed; the
 * architecture itself preserved as faithfully as the model allows.
 *
 * The cleaned image is then used:
 *   1. As the canvas for the painted preview shown to the user
 *   2. As the input image for {@link ReplicateNanoBananaSegmenter} so
 *      masks are aligned to the cleaned house, not the cluttered original
 *
 * Honest caveats: image-editing models still hallucinate. Expect the
 * cleaned image to have:
 *   - Pixel-level shifts versus the original (model regenerates everything)
 *   - Smoothed wall textures
 *   - Slightly different shadow / lighting interpretation
 *   - Possible loss of fine architectural detail
 *
 * The user explicitly requested this trade-off (cleaner masks at the cost
 * of generative regeneration). Opt-in by default; falls through silently
 * when not enabled.
 *
 * Configuration:
 *   replicate.image-cleaner.enabled       — kill switch (default false)
 *   replicate.image-cleaner.model         — default google/nano-banana-pro
 *                                           (best quality for architecture
 *                                            preservation; pro tier ~$0.10/call)
 *
 * Cost: ~$0.10 per clean on Nano Banana Pro. Doubles per-upload
 * Replicate spend when enabled (clean + mask = 2 calls).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCleanerService {

    private final RestTemplate restTemplate;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.image-cleaner.model:google/nano-banana-pro}")
    private String model;

    @Value("${replicate.image-cleaner.enabled:false}")
    private boolean enabled;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 90;

    public boolean isConfigured() {
        return enabled
                && replicateApiToken != null && !replicateApiToken.isBlank()
                && model != null && !model.isBlank();
    }

    /**
     * Runs the cleaning prompt on the input image. Returns the cleaned
     * image bytes on success. Empty on any failure (caller should fall
     * back to the original image).
     */
    public Optional<byte[]> cleanImage(String imageUrl) {
        if (!isConfigured()) {
            log.debug("ImageCleaner not configured — skipping");
            return Optional.empty();
        }
        try {
            log.info("ImageCleaner [{}]: cleaning image", model);

            Map<String, Object> input = Map.of(
                    "prompt", CLEAN_PROMPT,
                    "image_input", List.of(imageUrl),
                    "output_format", "jpg"
            );

            String predictionId = startPrediction(input);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("ImageCleaner prediction timed out");
                return Optional.empty();
            }
            String cleanedUrl = extractOutputUrl(result.get("output"));
            if (cleanedUrl == null) {
                log.warn("ImageCleaner returned no output URL");
                return Optional.empty();
            }
            byte[] bytes = downloadBytes(cleanedUrl);
            log.info("ImageCleaner produced cleaned image: {} bytes", bytes.length);
            return Optional.of(bytes);
        } catch (Exception e) {
            log.warn("ImageCleaner failed, falling back to original image: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The cleaning prompt — written to be as surgical as possible. Asks
     * the model to keep architecture pristine and ONLY remove clutter.
     * Generative models still drift, but this constrains it as much as
     * a text prompt can.
     */
    private static final String CLEAN_PROMPT =
            "Look at this photograph of a house. Edit the image to remove only "
          + "the unwanted clutter listed below, while keeping every architectural "
          + "element pristine and preserving the exact perspective, layout, "
          + "dimensions, materials, colors, lighting, and shadows.\n\n"
          + "REMOVE:\n"
          + "- Electrical wires, telephone wires, power lines, cables crossing the building\n"
          + "- Garbage, trash bags, construction debris on the ground\n"
          + "- Parked cars, motorcycles, scooters, bicycles directly in front of the house\n"
          + "- Tree branches, leaves, bushes that cover or obscure the wall surfaces\n"
          + "- Hanging laundry, temporary banners (not permanent signage)\n"
          + "- Construction scaffolding, ladders\n"
          + "- People and animals\n\n"
          + "KEEP COMPLETELY UNCHANGED:\n"
          + "- Every wall surface and its exact color/texture (cream paint, stone "
          + "cladding, brick, tile, plaster — all preserved as-is)\n"
          + "- Every architectural feature: doors, windows, window grilles, balconies, "
          + "railings, columns, parapets, moldings, ledges\n"
          + "- The roof, eaves, chimneys, AC units mounted on the wall, drainpipes "
          + "(these are part of the house, not clutter)\n"
          + "- Lighting, shadows, time of day, weather, sky\n"
          + "- Camera angle, perspective, framing, image dimensions\n"
          + "- The building's exact proportions — do NOT widen, narrow, or reshape it\n\n"
          + "OUTPUT: The same photograph with ONLY the listed clutter removed. The "
          + "house must remain pixel-faithful to the original — no stylization, no "
          + "color enhancement, no added architectural details, no smoothing of "
          + "textures, no relighting.\n";

    private String startPrediction(Map<String, Object> input) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + replicateApiToken);

            Map<String, Object> body = Map.of("input", input);
            String endpoint = REPLICATE_BASE + "/models/" + model + "/predictions";

            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return (String) response.getBody().get("id");
        } catch (Exception e) {
            log.warn("Failed to start ImageCleaner prediction: {}", e.getMessage());
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
                log.warn("ImageCleaner prediction terminal status: {} error: {}",
                        status, body.get("error"));
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractOutputUrl(Object output) {
        if (output == null) return null;
        if (output instanceof String s && !s.isBlank()) return s;
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String s) return s;
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
