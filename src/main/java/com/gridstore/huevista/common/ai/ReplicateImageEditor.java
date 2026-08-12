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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One image-edit prediction on Replicate, against whichever model it is handed.
 *
 * <p>The pipeline used to speak to exactly one model, so its request body could be
 * written inline at the call site. It now has a hierarchy to fall down — Nano Banana
 * Pro, then FLUX, then GPT Image, then Seedream — and those models do not accept the
 * same request. They all take a {@code prompt} and they all take the photo, but the
 * KEY the photo arrives under, and the way output size is asked for, differ per family:
 *
 * <pre>
 *   google/nano-banana-*        image_input: [url]   resolution: 1K|2K|4K
 *   black-forest-labs/flux-2-*  input_images: [url]  resolution: "1 MP"|"2 MP"|"4 MP"
 *   black-forest-labs/flux-kontext-*  input_image: url   (no resolution key)
 *   openai/gpt-image-*          input_images: [url]  quality: low|medium|high|auto
 *   bytedance/seedream-*        image_input: [url]   size: 1K|2K|4K
 * </pre>
 *
 * <p>Sending Nano Banana's body to FLUX gets a 422 and no image, which would look
 * exactly like "the fallback model is broken". So the schema lives here, keyed off the
 * model id, and every caller passes the same neutral {@link Spec}.
 *
 * <p>Everything that goes wrong leaves through {@link ImageEditException} carrying a
 * disposition, because with a chain of providers the only question that matters is
 * whether to ask this model again, ask the next one, or stop asking altogether. Two
 * cases are worth naming:
 *
 * <ul>
 *   <li>A <b>400/422</b> is this model rejecting an input key it does not know. The
 *       request is retried once with the optional tuning keys stripped, and only then
 *       failed over — a clean at the model's own default size beats no clean.</li>
 *   <li>A <b>401/403</b> is the Replicate token, not the model, so trying the next
 *       Replicate model in the chain would buy the same refusal three more times. That
 *       one gets its own type ({@link ReplicateAuthException}) so the caller can skip
 *       the rest of the platform and go straight to a non-Replicate provider.</li>
 * </ul>
 */
@Slf4j
@Component
public class ReplicateImageEditor {

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 90; // ~3 min

    private final RestTemplate restTemplate;

    @Value("${replicate.api-token:}")
    private String apiToken;

    /**
     * OpenAI key, forwarded as an INPUT to {@code openai/*} models on Replicate.
     * Those models are a thin wrapper around OpenAI's own API and bill the caller's
     * account, so Replicate requires the key in the prediction body. Without it the
     * provider is skipped rather than called and failed.
     */
    @Value("${openai.api-key:}")
    private String openAiApiKey;

    public ReplicateImageEditor(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** What one caller wants done, in terms no single model's schema owns. */
    public record Spec(
            String model,
            String prompt,
            String imageUrl,
            /** "match_input_image" where the family supports it; blank to omit. */
            String aspectRatio,
            /** "1K" / "2K" / "4K" in Nano Banana units; translated per family. Blank to omit. */
            String resolution,
            /** "jpg" or "png". */
            String outputFormat) {}

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * Whether this model can be called at all right now. Anything the model needs
     * beyond the Replicate token — currently only GPT Image's OpenAI key — is checked
     * here so an unusable provider is skipped silently instead of burning a minute of
     * the run's deadline proving it cannot work.
     */
    public boolean canRun(String model) {
        if (!hasToken() || model == null || model.isBlank()) return false;
        return familyOf(model) != Family.OPENAI || !isBlank(openAiApiKey);
    }

    /**
     * Runs one prediction to completion and returns the image bytes.
     *
     * @throws ImageEditException on every failure, classified for the caller
     */
    public byte[] edit(Spec spec) {
        Map<String, Object> input = buildInput(spec);
        String predictionId = startPrediction(spec.model(), input);
        Map<String, Object> result = pollUntilDone(predictionId);
        String url = extractOutputUrl(result.get("output"));
        if (url == null) {
            throw ImageEditException.failover("prediction succeeded but named no output image");
        }
        return downloadBytes(url);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-family request bodies
    // ─────────────────────────────────────────────────────────────────────────

    /** The request-schema families the chain can contain. */
    public enum Family {
        /** google/nano-banana-*, i.e. Gemini image. Also the shape used for unknown models. */
        NANO_BANANA,
        /** black-forest-labs/flux-2-* — image list, megapixel sizing. */
        FLUX,
        /** black-forest-labs/flux-kontext-* — ONE image, no size key. */
        FLUX_KONTEXT,
        /** openai/gpt-image-* — image list, needs the caller's OpenAI key. */
        OPENAI,
        /** bytedance/seedream-* — image list, sizes under "size". */
        SEEDREAM
    }

    /**
     * Which schema a model speaks, from its id alone. Matched on the recognisable part
     * of the name rather than the exact id so a newer tier in the same family
     * (flux-2-max, seedream-4-5, gpt-image-1-mini) is understood without a code change —
     * that is the whole point of keeping the chain in configuration.
     */
    public static Family familyOf(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("flux-kontext") || m.contains("flux-1-kontext")) return Family.FLUX_KONTEXT;
        if (m.contains("flux")) return Family.FLUX;
        if (m.contains("gpt-image") || m.startsWith("openai/")) return Family.OPENAI;
        if (m.contains("seedream") || m.startsWith("bytedance/")) return Family.SEEDREAM;
        return Family.NANO_BANANA;
    }

    private Map<String, Object> buildInput(Spec spec) {
        Family family = familyOf(spec.model());
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", spec.prompt());
        input.put("output_format", spec.outputFormat());

        switch (family) {
            case FLUX -> {
                input.put("input_images", List.of(spec.imageUrl()));
                putIfPresent(input, "resolution", megapixels(spec.resolution()));
                putIfPresent(input, "aspect_ratio", trimmed(spec.aspectRatio()));
            }
            case FLUX_KONTEXT -> {
                // Kontext edits exactly one image and has no size input at all —
                // it always returns at the input's resolution.
                input.put("input_image", spec.imageUrl());
                putIfPresent(input, "aspect_ratio", trimmed(spec.aspectRatio()));
            }
            case OPENAI -> {
                input.put("input_images", List.of(spec.imageUrl()));
                input.put("openai_api_key", openAiApiKey);
                input.put("number_of_images", 1);
                // GPT Image re-renders the whole frame; "high" fidelity is what keeps
                // the building recognisable rather than merely similar.
                input.put("input_fidelity", "high");
                input.put("quality", "high");
                // It has no match_input_image: "auto" is the nearest thing, letting it
                // pick the bucket closest to the photo instead of defaulting to square.
                putIfPresent(input, "aspect_ratio", openAiAspect(spec.aspectRatio()));
            }
            case SEEDREAM -> {
                input.put("image_input", List.of(spec.imageUrl()));
                // Seedream can return a SERIES from one prompt; we want the single edit.
                input.put("sequential_image_generation", "disabled");
                input.put("max_images", 1);
                putIfPresent(input, "size", trimmed(spec.resolution()));
                putIfPresent(input, "aspect_ratio", trimmed(spec.aspectRatio()));
            }
            case NANO_BANANA -> {
                input.put("image_input", List.of(spec.imageUrl()));
                putIfPresent(input, "resolution", trimmed(spec.resolution()));
                putIfPresent(input, "aspect_ratio", trimmed(spec.aspectRatio()));
            }
        }
        return input;
    }

    /** The keys that are tuning rather than substance — dropped on a 400/422 retry. */
    private static final List<String> OPTIONAL_KEYS = List.of(
            "resolution", "aspect_ratio", "size", "quality", "input_fidelity",
            "sequential_image_generation", "max_images", "number_of_images");

    /** FLUX sizes in megapixels; the config is written in Nano Banana's 1K/2K/4K. */
    static String megapixels(String resolution) {
        String value = trimmed(resolution);
        if (value == null) return null;
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "1K" -> "1 MP";
            case "2K" -> "2 MP";
            case "4K" -> "4 MP";
            default -> value; // already in FLUX units, or something only FLUX knows
        };
    }

    /** GPT Image has no "match_input_image"; "auto" is its closest equivalent. */
    static String openAiAspect(String aspectRatio) {
        String value = trimmed(aspectRatio);
        if (value == null) return null;
        return "match_input_image".equalsIgnoreCase(value) ? "auto" : value;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Replicate transport
    // ─────────────────────────────────────────────────────────────────────────

    private String startPrediction(String model, Map<String, Object> input) {
        try {
            return doStartPrediction(model, input);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new ReplicateAuthException("Replicate rejected our API token (" + status
                        + ") — check REPLICATE_API_TOKEN");
            }
            if (status == 429) {
                throw ImageEditException.retry("Replicate is throttling us: "
                        + status + " " + e.getResponseBodyAsString());
            }
            // 400/422 is almost always an input key this model version doesn't know.
            // Try once more with nothing but the prompt and the photo.
            boolean hasOptional = OPTIONAL_KEYS.stream().anyMatch(input::containsKey);
            if (hasOptional && (status == 400 || status == 422)) {
                log.warn("Replicate model {} rejected the optional inputs ({}), retrying with "
                        + "prompt + image only: {}", model, status, e.getResponseBodyAsString());
                Map<String, Object> slim = new HashMap<>(input);
                OPTIONAL_KEYS.forEach(slim::remove);
                try {
                    return doStartPrediction(model, slim);
                } catch (Exception retryError) {
                    throw ImageEditException.failover("could not be started even without the "
                            + "optional inputs: " + retryError.getMessage());
                }
            }
            // Anything else here (404 unknown model, 422 the slim body can't fix) is
            // about THIS model, not the photo — the next model in the chain may well
            // take it.
            throw ImageEditException.failover("Replicate rejected the request for " + model
                    + ": " + status + " " + e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            throw ImageEditException.retry("Replicate server error starting the prediction: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (ImageEditException e) {
            throw e;
        } catch (Exception e) {
            throw ImageEditException.retry("could not reach Replicate to start the prediction: " + e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String doStartPrediction(String model, Map<String, Object> input) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + apiToken);

        ResponseEntity<Map> response = restTemplate.exchange(
                REPLICATE_BASE + "/models/" + model + "/predictions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input), headers),
                Map.class);
        Map<String, Object> body = response.getBody();
        Object id = body == null ? null : body.get("id");
        if (id == null) {
            throw ImageEditException.failover("Replicate accepted the request for " + model
                    + " but returned no prediction id");
        }
        return String.valueOf(id);
    }

    /**
     * Waits for a started prediction to stop moving.
     *
     * <p>A prediction Replicate marks {@code failed} is an answer and usually carries a
     * reason, so it is classified from that text; running out of poll attempts means the
     * job was still going when we stopped watching, which is a different event and worth
     * a different provider rather than another go at this one.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> pollUntilDone(String predictionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + apiToken);
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ImageEditException.giveUp("waiting on the prediction was interrupted");
            }
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) continue;
            String status = (String) body.get("status");
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                String error = String.valueOf(body.get("error"));
                throw new ImageEditException("prediction " + status + ": " + error,
                        ImageEditException.classify(error));
            }
        }
        throw ImageEditException.failover("prediction was still running after "
                + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "s and was given up on");
    }

    /**
     * Replicate's output envelope varies by model: a bare URL string, a list of URLs, a
     * list of objects with a url field, or a dict. Handle all four so adding a model to
     * the chain doesn't need a parser change too.
     */
    static String extractOutputUrl(Object output) {
        if (output == null) return null;
        if (output instanceof String s && !s.isBlank()) return s;
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String s && !s.isBlank()) return s;
            if (first instanceof Map<?, ?> m && m.get("url") instanceof String s2) return s2;
        }
        if (output instanceof Map<?, ?> map) {
            for (String key : new String[]{"image", "output", "url"}) {
                if (map.get(key) instanceof String s) return s;
                if (map.get(key) instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof String s2) {
                    return s2;
                }
            }
        }
        return null;
    }

    private byte[] downloadBytes(String url) {
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw ImageEditException.failover("the output image at " + url + " was empty");
            }
            return body;
        } catch (ImageEditException e) {
            throw e;
        } catch (Exception e) {
            throw ImageEditException.failover("could not download the output image: " + e.getMessage());
        }
    }

    private static void putIfPresent(Map<String, Object> input, String key, String value) {
        if (value != null && !value.isBlank()) input.put(key, value);
    }

    private static String trimmed(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
