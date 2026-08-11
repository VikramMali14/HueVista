package com.gridstore.huevista.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Google's Gemini image models (the "Nano Banana" family) called DIRECTLY, on
 * generativelanguage.googleapis.com, with no Replicate in between.
 *
 * <p>This exists because of how the pipeline actually fails in production. The models
 * are the same either way — Replicate's {@code google/nano-banana-pro} IS Gemini 3 Pro
 * Image — but Replicate holds its own pool in front of them, and when that pool is full
 * a prediction comes back {@code failed} with {@code ModelRateLimitError: Service is
 * currently unavailable due to high demand (E003)}. Nothing is wrong with the photo,
 * the prompt or the key; there was simply no room. Going to Google directly is a
 * different queue, so the same request often succeeds immediately.
 *
 * <p>Two things differ from the Replicate call and both matter:
 *
 * <ul>
 *   <li><b>The image goes up, not across.</b> Replicate takes a URL and fetches it
 *       itself; Gemini wants the bytes inline as base64, so the caller passes the photo
 *       it already has rather than a link.</li>
 *   <li><b>There is no {@code match_input_image}.</b> Gemini generates into a fixed set
 *       of aspect buckets, so the nearest bucket has to be picked here from the photo's
 *       real dimensions. This is not cosmetic: every mask and every brush stroke in the
 *       studio is positioned against the cleaned canvas, so a canvas at a different
 *       aspect than the photo puts the walls in the wrong places.</li>
 * </ul>
 *
 * <p>Configuration — see {@code application.properties} for how to get a key:
 * <pre>
 *   google.gemini.api-key      GEMINI_API_KEY (blank = this provider is off)
 *   google.gemini.image-model  the image model id
 * </pre>
 */
@Slf4j
@Component
public class GeminiImageClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    /**
     * The aspect buckets the image models generate into. A request for anything else is
     * either rejected or silently rounded, so we round deliberately instead.
     */
    private static final Map<String, Double> ASPECT_BUCKETS = Map.of(
            "21:9", 21.0 / 9, "16:9", 16.0 / 9, "3:2", 3.0 / 2, "4:3", 4.0 / 3,
            "5:4", 5.0 / 4, "1:1", 1.0, "4:5", 4.0 / 5, "3:4", 3.0 / 4,
            "2:3", 2.0 / 3, "9:16", 9.0 / 16);

    private final RestTemplate restTemplate;

    @Value("${google.gemini.api-key:}")
    private String apiKey;

    @Value("${google.gemini.image-model:gemini-3-pro-image-preview}")
    private String imageModel;

    public GeminiImageClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return imageModel;
    }

    /**
     * Edit {@code source} according to {@code prompt} and return the image that comes
     * back.
     *
     * @param imageSize "1K" / "2K" / "4K", or blank to let the model choose. Passed
     *                  through only when it is one of those — the parameter is model
     *                  specific and an unknown value fails the whole request.
     * @throws ImageEditException always, when no image is produced, carrying whether
     *         the caller should retry, fail over, or stop.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public byte[] edit(String prompt, byte[] source, String imageSize) {
        if (!isConfigured()) {
            throw ImageEditException.giveUp("Gemini API key is not set");
        }
        String aspect = nearestAspectRatio(source);
        Map<String, Object> imageConfig = new LinkedHashMap<>();
        if (aspect != null) imageConfig.put("aspectRatio", aspect);
        if (isSupportedImageSize(imageSize)) imageConfig.put("imageSize", imageSize.trim().toUpperCase(Locale.ROOT));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        // Both TEXT and IMAGE: the image models narrate what they did alongside the
        // picture, and asking for IMAGE alone is rejected by some of them.
        generationConfig.put("responseModalities", List.of("TEXT", "IMAGE"));
        if (!imageConfig.isEmpty()) generationConfig.put("imageConfig", imageConfig);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("inline_data", Map.of(
                                        "mime_type", sniffMimeType(source),
                                        "data", Base64.getEncoder().encodeToString(source))),
                                Map.of("text", prompt)))),
                "generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Header rather than ?key=… so the secret never lands in a URL, an access log,
        // or an exception message built from the request URI.
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> response;
        try {
            ResponseEntity<Map> raw = restTemplate.exchange(
                    BASE + imageModel + ":generateContent",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            response = raw.getBody();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            String detail = status + " " + e.getResponseBodyAsString();
            // 429 is Google's own rate limit and the one worth waiting out. 401/403 is a
            // bad or unenabled key and 400 is a malformed request — both are deployment
            // problems that another attempt cannot fix.
            throw status == 429
                    ? ImageEditException.retry("Gemini rate limited: " + detail)
                    : ImageEditException.giveUp("Gemini refused the request: " + detail);
        } catch (HttpServerErrorException e) {
            throw ImageEditException.retry("Gemini server error: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw ImageEditException.retry("Gemini call failed: " + e);
        }

        String blocked = blockReason(response);
        if (blocked != null) {
            throw ImageEditException.giveUp("Gemini blocked the request: " + blocked);
        }
        byte[] image = firstInlineImage(response);
        if (image == null) {
            throw ImageEditException.failover("Gemini returned no image part");
        }
        log.info("Gemini [{}] produced cleaned image: {} bytes (aspect={}, size={})",
                imageModel, image.length, aspect, isSupportedImageSize(imageSize) ? imageSize : "model default");
        return image;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Response reading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The first inline image in the first candidate.
     *
     * <p>Tolerates both spellings of the payload keys on purpose: the REST API accepts
     * {@code inline_data}/{@code mime_type} on the way in but answers in camelCase, and
     * that asymmetry is exactly the kind of thing that changes without warning.
     */
    @SuppressWarnings("unchecked")
    static byte[] firstInlineImage(Map<String, Object> response) {
        if (response == null) return null;
        Object candidates = response.get("candidates");
        if (!(candidates instanceof List<?> list) || list.isEmpty()) return null;
        for (Object candidate : list) {
            if (!(candidate instanceof Map<?, ?> c)) continue;
            if (!(c.get("content") instanceof Map<?, ?> content)) continue;
            if (!(content.get("parts") instanceof List<?> parts)) continue;
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> p)) continue;
                Object inline = p.get("inlineData") != null ? p.get("inlineData") : p.get("inline_data");
                if (!(inline instanceof Map<?, ?> data)) continue;
                Object encoded = data.get("data");
                if (encoded instanceof String s && !s.isBlank()) {
                    try {
                        return Base64.getDecoder().decode(s);
                    } catch (IllegalArgumentException notBase64) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Why the model produced nothing, when it says so explicitly — a prompt-level
     * {@code promptFeedback.blockReason}, or a candidate that stopped for a safety
     * reason rather than because it was done.
     */
    static String blockReason(Map<String, Object> response) {
        if (response == null) return null;
        if (response.get("promptFeedback") instanceof Map<?, ?> feedback
                && feedback.get("blockReason") instanceof String reason && !reason.isBlank()) {
            return reason;
        }
        if (response.get("candidates") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> candidate
                && candidate.get("finishReason") instanceof String finish
                && !finish.isBlank()
                && !"STOP".equals(finish) && !"MAX_TOKENS".equals(finish)) {
            return finish;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Aspect ratio
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The supported bucket closest to this image's own aspect, or null when the bytes
     * cannot be read (in which case the model picks, which beats guessing wrong).
     *
     * <p>Nearest is measured in log space so it is symmetric: 16:9 is as far from 1:1 as
     * 9:16 is, which a plain difference of ratios does not give you.
     */
    static String nearestAspectRatio(byte[] image) {
        int[] dimensions = dimensionsOf(image);
        if (dimensions == null) return null;
        double actual = (double) dimensions[0] / dimensions[1];
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, Double> bucket : ASPECT_BUCKETS.entrySet()) {
            double distance = Math.abs(Math.log(actual) - Math.log(bucket.getValue()));
            // Ties broken by name so the choice is deterministic rather than
            // dependent on the map's iteration order.
            if (distance < bestDistance
                    || (distance == bestDistance && best != null && bucket.getKey().compareTo(best) < 0)) {
                bestDistance = distance;
                best = bucket.getKey();
            }
        }
        return best;
    }

    private static int[] dimensionsOf(byte[] image) {
        if (image == null || image.length == 0) return null;
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(image));
            if (read == null || read.getWidth() <= 0 || read.getHeight() <= 0) return null;
            return new int[]{read.getWidth(), read.getHeight()};
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSupportedImageSize(String size) {
        if (size == null) return false;
        String s = size.trim().toUpperCase(Locale.ROOT);
        return s.equals("1K") || s.equals("2K") || s.equals("4K");
    }

    /**
     * PNG or JPEG from the magic bytes. The uploads this sees are one or the other, and
     * declaring the wrong one is rejected by the API rather than tolerated.
     */
    static String sniffMimeType(byte[] image) {
        if (image != null && image.length >= 8
                && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
            return "image/png";
        }
        if (image != null && image.length >= 3
                && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (image != null && image.length >= 12
                && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
