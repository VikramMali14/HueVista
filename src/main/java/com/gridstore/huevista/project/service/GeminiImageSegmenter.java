package com.gridstore.huevista.project.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls Google's Gemini Image model (a.k.a. "Nano Banana" / "Nano Banana 2")
 * to generate per-surface paint masks. Send the original photo with a
 * structured prompt asking for a black-and-white mask of one specific
 * paintable surface; receive back an image where white = that surface,
 * black = everything else.
 *
 * Honest caveat: this is image GENERATION, not pixel extraction. The
 * model can misalign mask boundaries by a few pixels vs the source.
 * Recent Gemini Image versions are dramatically better at pixel-aligned
 * editing than earlier diffusion models, but it's still not a
 * semantic-segmentation model in the strict sense. We expose it as one
 * of several primary paths so the user can compare quality and pick
 * the best for their photo distribution.
 *
 * Configuration:
 *   google.gemini.api-key            — required to enable this path
 *   google.gemini.image-model        — default "gemini-3-pro-image-preview"
 *                                      (Nano Banana 2). Override to
 *                                      "gemini-2.5-flash-image-preview"
 *                                      for the cheaper/faster older model.
 *   google.gemini.image.enabled      — toggle; defaults to true when
 *                                      api-key is set.
 *
 * Cost: ~$0.04 per generated mask on Gemini 3 Pro Image. User explicitly
 * asked to prioritize result quality over cost. One call per category
 * (MAIN_WALL, ACCENT_WALL, TRIM), so up to ~$0.12 per upload when all
 * three are requested.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiImageSegmenter {

    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;

    @Value("${google.gemini.api-key:}")
    private String apiKey;

    @Value("${google.gemini.image-model:gemini-3-pro-image-preview}")
    private String model;

    @Value("${google.gemini.image.enabled:true}")
    private boolean enabled;

    private static final String GEMINI_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models";

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Generates a binary mask for one paintable surface category.
     *
     * @param imageBytes      the original photo (JPEG or PNG)
     * @param surfaceDescription a natural-language description of the
     *                           surface (e.g. "the main painted wall of
     *                           this house — exclude windows, doors,
     *                           stone cladding, and brick")
     * @return mask bytes (PNG, white = surface, black = elsewhere) or
     *         Optional.empty() on failure
     */
    public Optional<byte[]> generateMask(byte[] imageBytes, String surfaceDescription) {
        if (!isConfigured()) {
            log.debug("Gemini Image segmenter not configured");
            return Optional.empty();
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = sniffMediaType(imageBytes);

            String prompt = buildMaskPrompt(surfaceDescription);

            Map<String, Object> imagePart = Map.of(
                    "inline_data", Map.of(
                            "mime_type", mediaType,
                            "data", base64
                    )
            );
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(imagePart, textPart)
                    )),
                    "generationConfig", Map.of(
                            "responseModalities", List.of("IMAGE", "TEXT"),
                            "temperature", 0.1
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = GEMINI_BASE + "/" + model + ":generateContent?key=" + apiKey;
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            byte[] maskBytes = extractInlineImage(response.getBody());
            if (maskBytes == null) {
                log.warn("Gemini Image returned no image part for '{}'", surfaceDescription);
                return Optional.empty();
            }
            log.info("Gemini Image generated mask for '{}': {} bytes", surfaceDescription, maskBytes.length);
            return Optional.of(maskBytes);

        } catch (Exception e) {
            log.warn("Gemini Image mask generation failed for '{}': {}", surfaceDescription, e.getMessage());
            return Optional.empty();
        }
    }

    private String buildMaskPrompt(String surfaceDescription) {
        return ("You are a precise image-segmentation tool. The user has uploaded a "
                + "photograph. Your job is to produce a BINARY MASK IMAGE of the same "
                + "exact dimensions as the input.\n\n"
                + "TARGET SURFACE: " + surfaceDescription + "\n\n"
                + "OUTPUT REQUIREMENTS:\n"
                + "- Pure WHITE (#FFFFFF) pixels for EVERY pixel that belongs to the target surface.\n"
                + "- Pure BLACK (#000000) pixels for everything else (sky, ground, vegetation, "
                + "doors, windows, stone, brick, fixtures, vehicles, decor, furniture, etc.).\n"
                + "- The mask must be PIXEL-ALIGNED with the input photo: edges of the surface "
                + "in the mask must coincide with the same edges in the input.\n"
                + "- Do NOT include any other colors. No grey. No gradients. No anti-aliasing.\n"
                + "- Do NOT add text, watermarks, or annotations.\n"
                + "- The output image must be the SAME RESOLUTION as the input image.\n\n"
                + "Generate the mask now. Return ONLY the mask image — no commentary.\n");
    }

    @SuppressWarnings("unchecked")
    private byte[] extractInlineImage(Map<String, Object> body) {
        if (body == null) return null;
        Object candidates = body.get("candidates");
        if (!(candidates instanceof List<?> candList) || candList.isEmpty()) return null;
        Object first = candList.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object content = firstMap.get("content");
        if (!(content instanceof Map<?, ?> contentMap)) return null;
        Object parts = contentMap.get("parts");
        if (!(parts instanceof List<?> partList)) return null;

        for (Object p : partList) {
            if (!(p instanceof Map<?, ?> pm)) continue;
            // The API uses either "inline_data" or "inlineData" depending on version.
            Object inline = pm.get("inlineData");
            if (inline == null) inline = pm.get("inline_data");
            if (!(inline instanceof Map<?, ?> idMap)) continue;
            Object data = idMap.get("data");
            if (!(data instanceof String b64)) continue;
            try {
                return Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                log.warn("Gemini Image returned undecodable base64");
                return null;
            }
        }
        return null;
    }

    private static String sniffMediaType(byte[] bytes) {
        if (bytes != null && bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        return "image/jpeg";
    }
}
