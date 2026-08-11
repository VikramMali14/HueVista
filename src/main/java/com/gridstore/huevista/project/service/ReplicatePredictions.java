package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Running one prediction on Replicate: start it, wait for it, get the image back.
 *
 * Every image model in this product is reached the same way — POST an input map to
 * {@code /models/{owner}/{name}/predictions}, poll the prediction until it stops moving,
 * then follow the output URL — and the interesting parts are the two places that
 * expectation breaks. A model version that has never heard of {@code resolution} or
 * {@code aspect_ratio} answers 400 or 422, so those keys are dropped and the call retried
 * once: an image at the model's own default beats no image. And a prediction can sit in
 * {@code starting} indefinitely, so polling has a deadline rather than a promise.
 *
 * <p>This is deliberately a helper and not a base class. The three services that talk to
 * Replicate disagree about almost everything above this layer — which model, which input
 * key holds the images, what a failure should do — and the only thing genuinely shared is
 * the HTTP conversation.
 *
 * <p>{@link ImageCleanerService} and {@link ReplicateMaskSegmenter} still carry their own
 * copies of this conversation. Folding them in is worth doing and is deliberately not done
 * here: they are the two most load-bearing calls in the pipeline, and this change already
 * moves enough. New callers use this one so the count stops growing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplicatePredictions {

    private final RestTemplate restTemplate;

    @Value("${replicate.api-token:}")
    private String apiToken;

    private static final String BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 90;

    /** Input keys that are tuning rather than substance, and may be dropped on a 400. */
    private static final List<String> OPTIONAL_KEYS = List.of("resolution", "aspect_ratio");

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * Run {@code input} through {@code model} and return the bytes of the image it
     * produced.
     *
     * @param label what to call this call in the logs — there are several, and "prediction
     *              failed" on its own has never once been enough to tell them apart.
     * @throws ExternalServiceException when the model refuses, fails, or does not finish.
     *         Fails LOUD, unlike the cleaner, which falls back to the original photo: a
     *         render has no fallback. Its whole value is being the generated image, so a
     *         caller has to be told rather than handed something else.
     */
    public byte[] runToImage(String model, Map<String, Object> input, String label) {
        if (!isConfigured()) {
            throw new ExternalServiceException("Image generation is not configured.");
        }
        String predictionId = start(model, input, label);
        Map<String, Object> finished = poll(predictionId, label);
        String url = extractOutputUrl(finished.get("output"));
        if (url == null || url.isBlank()) {
            throw new ExternalServiceException(label + " finished without returning an image.");
        }
        return download(url);
    }

    private String start(String model, Map<String, Object> input, String label) {
        try {
            return doStart(model, input);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            boolean hadOptional = OPTIONAL_KEYS.stream().anyMatch(input::containsKey);
            if (hadOptional && (status == 400 || status == 422)) {
                log.warn("{} model rejected optional inputs ({}), retrying without {}: {}",
                        label, status, OPTIONAL_KEYS, e.getResponseBodyAsString());
                Map<String, Object> slim = new HashMap<>(input);
                OPTIONAL_KEYS.forEach(slim::remove);
                try {
                    return doStart(model, slim);
                } catch (Exception retryFailed) {
                    throw new ExternalServiceException(
                            label + " could not be started: " + retryFailed.getMessage());
                }
            }
            log.warn("Failed to start {} prediction: {} {}", label, status, e.getResponseBodyAsString());
            throw new ExternalServiceException(label + " could not be started.");
        } catch (Exception e) {
            log.warn("Failed to start {} prediction: {}", label, e.getMessage());
            throw new ExternalServiceException(label + " could not be started.");
        }
    }

    @SuppressWarnings("rawtypes")
    private String doStart(String model, Map<String, Object> input) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + apiToken);

        ResponseEntity<Map> response = restTemplate.exchange(
                BASE + "/models/" + model + "/predictions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input), headers),
                Map.class);
        Map body = response.getBody();
        Object id = body == null ? null : body.get("id");
        if (!(id instanceof String s) || s.isBlank()) {
            throw new ExternalServiceException("Replicate accepted the job but named no prediction.");
        }
        return s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> poll(String predictionId, String label) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + apiToken);
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ExternalServiceException(label + " was interrupted.");
            }
            ResponseEntity<Map> response = restTemplate.exchange(
                    BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) continue;
            String status = (String) body.get("status");
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("{} prediction {}: {}", label, status, body.get("error"));
                throw new ExternalServiceException(label + " could not be produced.");
            }
        }
        throw new ExternalServiceException(label + " took too long and was given up on.");
    }

    /** Replicate returns a bare URL, a list of them, or an object holding one. */
    static String extractOutputUrl(Object output) {
        if (output == null) return null;
        if (output instanceof String s && !s.isBlank()) return s;
        if (output instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return s;
        }
        if (output instanceof Map<?, ?> map) {
            for (String key : new String[]{"image", "output", "url"}) {
                if (map.get(key) instanceof String s) return s;
            }
        }
        return null;
    }

    private byte[] download(String url) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new ExternalServiceException("Empty response downloading the generated image.");
        }
        return body;
    }
}
