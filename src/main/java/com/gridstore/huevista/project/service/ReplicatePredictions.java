package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.ai.ImageEditException;
import com.gridstore.huevista.common.ai.ReplicateAuthException;
import com.gridstore.huevista.common.ai.ReplicateConcurrencyGate;
import com.gridstore.huevista.common.ai.ReplicateImageEditor;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Running one prediction on Replicate: start it, wait for it, get the image back — and keep
 * asking until somebody produces the image or the budget runs out.
 *
 * <p>Every image model in this product is reached the same way — POST an input map to
 * {@code /models/{owner}/{name}/predictions}, poll the prediction until it stops moving,
 * then follow the output URL. The interesting parts are the places that expectation breaks.
 *
 * <p><b>A model that is merely busy used to end the render.</b> Replicate answers a model
 * with no capacity by completing the prediction as {@code failed} and putting the reason in
 * free text: {@code ModelRateLimitError: Service is currently unavailable due to high demand
 * (E003)}. That arrived here as an ordinary failure and was reported to the customer as
 * "your image could not be made", refunding the credit for a condition that clears in
 * seconds. It is now read through {@link ImageEditException#classify}, which the clean path
 * has always used and which knows E003 by name, and a busy model is asked again after a
 * growing, jittered wait before the next model in the chain is tried at all.
 *
 * <p><b>A refusal is still final.</b> A safety block or an invalid input gets the same answer
 * from every model, so it stops the chain immediately rather than spending the customer's
 * wait proving it four more times. That distinction is the whole reason the classifier
 * exists.
 *
 * <p><b>A model version that has never heard of {@code resolution} or {@code aspect_ratio}</b>
 * answers 400 or 422, so those keys are dropped and the call retried once: an image at the
 * model's own default beats no image.
 *
 * <p><b>Nothing here waits forever.</b> Polling has a deadline, and so does the chain as a
 * whole — see {@link #totalBudgetMs}.
 *
 * <p>This is deliberately a helper and not a base class. {@link ImageCleanerService} and
 * {@link ReplicateMaskSegmenter} still carry their own copies of this conversation; folding
 * them in is worth doing and is deliberately not done here. They are the two most
 * load-bearing calls in the pipeline, and the clean already has its own retry and failover
 * chain, so this change fixes the path that had none.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplicatePredictions {

    private final RestTemplate restTemplate;
    private final ReplicateImageEditor imageEditor;
    private final ReplicateConcurrencyGate gate;

    @Value("${replicate.api-token:}")
    private String apiToken;

    /** How many times ONE model is asked before the chain moves on. */
    @Value("${replicate.predictions.max-attempts:3}")
    private int maxAttempts;

    /** First wait after a busy model; doubles per attempt up to {@link #maxBackoffMs}. */
    @Value("${replicate.predictions.retry-backoff-ms:5000}")
    private long retryBackoffMs;

    @Value("${replicate.predictions.max-backoff-ms:45000}")
    private long maxBackoffMs;

    /**
     * The ceiling on one whole call — every model, every attempt, every wait.
     *
     * <p>It exists because of the sweeper. {@link ProjectRenderSweeper} presumes a render
     * dead once it has gone {@code app.render.stranded-after-minutes} without finishing
     * (30 by default, never less than 10) and refunds it. A retry chain that outlived that
     * would have the sweeper refund a customer whose image then arrives — the one outcome
     * worse than being late. Eight minutes stays clear of even the floor.
     */
    @Value("${replicate.predictions.total-budget-ms:480000}")
    private long totalBudgetMs;

    /** Gap between status checks on a running prediction. Configurable so tests are not
     *  obliged to spend two real seconds per poll. */
    @Value("${replicate.predictions.poll-interval-ms:2000}")
    private long pollIntervalMs;

    private static final String BASE = "https://api.replicate.com/v1";
    private static final int MAX_POLL_ATTEMPTS = 90;

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * What the caller wants made, in terms no single model's schema owns.
     *
     * @param models   the chain, best first. Later entries are only reached when the ones
     *                 before them are out of capacity or refuse for a reason that is not
     *                 about the image itself.
     * @param imageUrls the source images in the order the model should read them — for a
     *                 render, the cleaned photo and then the region masks.
     */
    public record Ask(List<String> models, String prompt, List<String> imageUrls,
                      String resolution, String aspectRatio, String outputFormat) {}

    /**
     * Run {@code ask} down its model chain and return the bytes of the first image produced.
     *
     * @param label what to call this call in the logs — there are several, and "prediction
     *              failed" on its own has never once been enough to tell them apart.
     * @throws ExternalServiceException when every model in the chain refuses, fails, or does
     *         not finish. Fails LOUD, unlike the cleaner, which falls back to the original
     *         photo: a render has no fallback OUTPUT. Its whole value is being the generated
     *         image, so a caller has to be told rather than handed something else — which is
     *         exactly why it is worth trying more than one model before saying so.
     */
    public byte[] run(Ask ask, String label) {
        if (!isConfigured()) {
            throw new ExternalServiceException("Image generation is not configured.");
        }
        List<String> chain = runnableModels(ask.models(), label);
        if (chain.isEmpty()) {
            throw new ExternalServiceException(label + " has no model configured to run it.");
        }

        long deadline = System.currentTimeMillis() + Math.max(30_000L, totalBudgetMs);
        String lastReason = null;

        for (String model : chain) {
            if (outOfTime(deadline)) {
                log.warn("{}: out of time before trying {}", label, model);
                break;
            }
            Outcome outcome = askModel(model, ask, label, deadline);
            if (outcome.image() != null) return outcome.image();
            lastReason = outcome.reason();
            if (!outcome.tryAnotherModel()) break;
        }

        throw new ExternalServiceException(label + " could not be produced."
                + (lastReason == null ? "" : " (" + lastReason + ")"));
    }

    /** What asking one model came to: the image, or why there isn't one and what to do next. */
    private record Outcome(byte[] image, String reason, boolean tryAnotherModel) {
        static Outcome of(byte[] image) {
            return new Outcome(image, null, false);
        }

        static Outcome failover(String reason) {
            return new Outcome(null, reason, true);
        }

        static Outcome stop(String reason) {
            return new Outcome(null, reason, false);
        }
    }

    /**
     * Ask one model, retrying it while it is merely busy.
     *
     * <p>The three endings are three different operational problems and are logged as such:
     * the model is out of capacity, the model refused the request, or the model never
     * answered. Reporting all of them as "prediction failed" is what made the original
     * failure so hard to read.
     */
    private Outcome askModel(String model, Ask ask, String label, long deadline) {
        int attempts = Math.max(1, maxAttempts);
        String lastReason = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Map<String, Object> input = imageEditor.buildInput(model, ask.prompt(),
                        ask.imageUrls(), ask.aspectRatio(), ask.resolution(), ask.outputFormat());
                byte[] image = gate.call(label, () -> runOnce(model, input, label, deadline));
                if (attempt > 1) {
                    log.info("{} [{}]: succeeded on attempt {}/{}", label, model, attempt, attempts);
                }
                return Outcome.of(image);

            } catch (ReplicateAuthException e) {
                // The account, not the model. Every other Replicate model would discover the
                // same dead token, so there is nothing to fail over to.
                log.error("{} [{}]: {}", label, model, e.getMessage());
                return Outcome.stop("Replicate rejected our credentials");

            } catch (ImageEditException e) {
                lastReason = e.getMessage();
                if (!e.worthFailingOver()) {
                    log.warn("{} [{}] refused the job — not retried anywhere: {}",
                            label, model, lastReason);
                    return Outcome.stop(lastReason);
                }
                if (!e.retryable() || attempt == attempts) {
                    log.warn("{} [{}] gave up after {} attempt(s): {}",
                            label, model, attempt, lastReason);
                    return Outcome.failover(lastReason);
                }
                long waitMs = backoffMs(attempt);
                if (System.currentTimeMillis() + waitMs >= deadline) {
                    log.warn("{} [{}] is busy and there is no time left to wait: {}",
                            label, model, lastReason);
                    return Outcome.failover(lastReason);
                }
                log.warn("{} [{}] attempt {}/{} failed, retrying in {}ms: {}",
                        label, model, attempt, attempts, waitMs, lastReason);
                if (!pause(waitMs)) return Outcome.stop("interrupted");

            } catch (Exception e) {
                // Anything unmapped is treated as transport trouble rather than a verdict on
                // the request, so the next model still gets its turn.
                lastReason = e.toString();
                log.warn("{} [{}] attempt {}/{} threw: {}", label, model, attempt, attempts, lastReason);
                if (attempt == attempts) return Outcome.failover(lastReason);
                long waitMs = backoffMs(attempt);
                if (System.currentTimeMillis() + waitMs >= deadline) return Outcome.failover(lastReason);
                if (!pause(waitMs)) return Outcome.stop("interrupted");
            }
        }
        return Outcome.failover(lastReason);
    }

    /** One full prediction: start it, poll it, download what it made. */
    private byte[] runOnce(String model, Map<String, Object> input, String label, long deadline) {
        String predictionId = start(model, input, label);
        Map<String, Object> finished = poll(predictionId, label, deadline);
        String url = extractOutputUrl(finished.get("output"));
        if (url == null || url.isBlank()) {
            throw ImageEditException.failover(label + " finished without returning an image.");
        }
        return download(url);
    }

    /**
     * The chain with the unusable entries removed: blanks, duplicates, and models this
     * deployment cannot call at all (an {@code openai/*} model with no OpenAI key). Skipping
     * those here is what stops a fallback burning a minute of the budget proving it cannot
     * work.
     */
    private List<String> runnableModels(List<String> models, String label) {
        if (models == null) return List.of();
        LinkedHashSet<String> ordered = models.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> runnable = new ArrayList<>();
        for (String model : ordered) {
            if (imageEditor.canRun(model)) {
                runnable.add(model);
            } else {
                log.info("{} skipping {} — it is not configured "
                        + "(openai/* models need OPENAI_API_KEY)", label, model);
            }
        }
        return runnable;
    }

    /** Split a comma-separated model property into a chain. */
    public static List<String> chainOf(String primary, String fallbacks) {
        List<String> chain = new ArrayList<>();
        if (primary != null && !primary.isBlank()) chain.add(primary.trim());
        if (fallbacks != null && !fallbacks.isBlank()) {
            Arrays.stream(fallbacks.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(chain::add);
        }
        return chain;
    }

    /**
     * Exponential backoff with equal jitter: half the computed wait, plus a random amount up
     * to the other half.
     *
     * <p>The jitter is the part that matters under load. Sixteen render threads that all met
     * the same busy model would otherwise re-ask it at the same instant, three times over —
     * a thundering herd aimed squarely at a service that just said it had no capacity. The
     * fixed half keeps every retry meaningfully later than the failure; the random half
     * spreads the herd across the window.
     */
    long backoffMs(int attempt) {
        long base = Math.max(100L, retryBackoffMs);
        long cap = Math.max(base, maxBackoffMs);
        long exponential = base;
        for (int i = 1; i < attempt && exponential < cap; i++) {
            exponential = Math.min(cap, exponential * 2);
        }
        long half = exponential / 2;
        return half + (half > 0 ? ThreadLocalRandom.current().nextLong(half) : 0);
    }

    private static boolean outOfTime(long deadline) {
        return System.currentTimeMillis() >= deadline;
    }

    /** @return false when the wait was interrupted and the caller should stop. */
    private boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Backoff interrupted — abandoning the call");
            return false;
        }
    }

    // ── Replicate transport ─────────────────────────────────────────────────

    private String start(String model, Map<String, Object> input, String label) {
        try {
            return doStart(model, input);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new ReplicateAuthException("Replicate rejected our API token (" + status
                        + ") — check REPLICATE_API_TOKEN");
            }
            if (status == 429) {
                throw ImageEditException.retry("Replicate is throttling us: " + status + " "
                        + e.getResponseBodyAsString());
            }
            // 400/422 is almost always an input key this model version doesn't know.
            boolean hadOptional = ReplicateImageEditor.OPTIONAL_KEYS.stream().anyMatch(input::containsKey);
            if (hadOptional && (status == 400 || status == 422)) {
                log.warn("{} model {} rejected optional inputs ({}), retrying without them: {}",
                        label, model, status, e.getResponseBodyAsString());
                Map<String, Object> slim = new HashMap<>(input);
                ReplicateImageEditor.OPTIONAL_KEYS.forEach(slim::remove);
                try {
                    return doStart(model, slim);
                } catch (Exception retryFailed) {
                    throw ImageEditException.failover(label + " could not be started even "
                            + "without the optional inputs: " + retryFailed.getMessage());
                }
            }
            // Anything else here (404 unknown model, 422 the slim body can't fix) is about
            // THIS model, not the request — the next model may well take it.
            throw ImageEditException.failover("Replicate rejected the request for " + model
                    + ": " + status + " " + e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            throw ImageEditException.retry("Replicate server error starting the prediction: "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (ImageEditException e) {
            // Includes ReplicateAuthException, which askModel() catches separately: every
            // model on the chain would discover the same dead token.
            throw e;
        } catch (Exception e) {
            throw ImageEditException.retry("could not reach Replicate to start the prediction: " + e);
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
            throw ImageEditException.failover("Replicate accepted the job but named no prediction.");
        }
        return s;
    }

    /**
     * Wait for a prediction to stop moving.
     *
     * <p>A prediction that ends {@code failed} is where a busy model actually surfaces: the
     * HTTP call succeeded, the prediction ran, and the reason is free text in {@code error}.
     * Handing that text to the classifier is what turns "your image could not be made" back
     * into a retry.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> poll(String predictionId, String label, long deadline) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + apiToken);
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            if (outOfTime(deadline)) {
                throw ImageEditException.failover(label + " ran out of time waiting for "
                        + "the model to finish.");
            }
            if (!pause(Math.max(1L, pollIntervalMs))) {
                throw ImageEditException.giveUp(label + " was interrupted.");
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
                String error = body.get("error") == null ? null : String.valueOf(body.get("error"));
                log.warn("{} prediction {}: {}", label, status, error);
                throw new ImageEditException(
                        error == null || error.isBlank() ? label + " " + status : error,
                        ImageEditException.classify(error));
            }
        }
        throw ImageEditException.failover(label + " took too long and was given up on.");
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
            throw ImageEditException.failover("Empty response downloading the generated image.");
        }
        return body;
    }
}
