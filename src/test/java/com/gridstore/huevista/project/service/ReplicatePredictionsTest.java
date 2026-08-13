package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.ai.ReplicateConcurrencyGate;
import com.gridstore.huevista.common.ai.ReplicateImageEditor;
import com.gridstore.huevista.common.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The render's answer to a model that has no capacity.
 *
 * <p>These are written against the failure that actually happened. Replicate does not
 * report a busy model with an HTTP error — it completes the prediction as {@code failed}
 * and puts {@code ModelRateLimitError ... high demand (E003)} in a free-text field. That
 * used to end the render and refund the customer's credit for a condition that clears in
 * seconds, so the cases below are mostly about which endings are worth another go.
 */
class ReplicatePredictionsTest {

    /** The exact text Replicate returned when Nano Banana Pro had no capacity. */
    private static final String E003 = "Prediction failed: Async prediction failed: "
            + "ModelRateLimitError: Service is currently unavailable due to high demand. "
            + "Please try again later. (E003) (1cah9wlWR9)";

    private static final byte[] IMAGE = "jpeg-bytes".getBytes();

    private RestTemplate restTemplate;
    private ReplicatePredictions predictions;

    /** The prediction status payloads to hand back, in order, one per poll. */
    private final Deque<Map<String, Object>> polls = new ArrayDeque<>();
    /** Every model a prediction was started against, in order. */
    private final List<String> started = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        ReplicateImageEditor editor = new ReplicateImageEditor(mock(RestTemplate.class));
        ReflectionTestUtils.setField(editor, "apiToken", "tok");

        predictions = new ReplicatePredictions(restTemplate, editor,
                new ReplicateConcurrencyGate(4, 5_000));
        ReflectionTestUtils.setField(predictions, "apiToken", "tok");
        ReflectionTestUtils.setField(predictions, "maxAttempts", 3);
        // Real waits, but short ones: the backoff is exercised, the suite is not delayed.
        ReflectionTestUtils.setField(predictions, "retryBackoffMs", 2L);
        ReflectionTestUtils.setField(predictions, "maxBackoffMs", 4L);
        ReflectionTestUtils.setField(predictions, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(predictions, "totalBudgetMs", 60_000L);

        // POST /models/{model}/predictions -> always accepted, records which model.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(call -> {
                    String url = call.getArgument(0);
                    started.add(url.substring(url.indexOf("/models/") + 8,
                            url.indexOf("/predictions")));
                    return ResponseEntity.ok(Map.of("id", "pred-" + started.size()));
                });

        // GET /predictions/{id} -> the next scripted status.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(call -> ResponseEntity.ok(
                        polls.isEmpty() ? succeeded() : polls.poll()));

        // GET the output url -> the bytes.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(IMAGE));
    }

    private static Map<String, Object> succeeded() {
        return Map.of("status", "succeeded", "output", "http://replicate/out.jpg");
    }

    private static Map<String, Object> failedWith(String error) {
        return Map.of("status", "failed", "error", error);
    }

    private ReplicatePredictions.Ask ask(String... models) {
        return new ReplicatePredictions.Ask(List.of(models), "repaint the walls",
                List.of("http://photo", "http://mask-1"), "2K", "match_input_image", "jpg");
    }

    @Test
    void aBusyModelIsAskedAgainRatherThanFailingTheRender() {
        // The bug: this exact string ended the render on the first try.
        polls.add(failedWith(E003));
        polls.add(succeeded());

        byte[] image = predictions.run(ask("google/nano-banana-pro"), "Render");

        assertThat(image).isEqualTo(IMAGE);
        assertThat(started).containsExactly("google/nano-banana-pro", "google/nano-banana-pro");
    }

    @Test
    void aModelThatStaysBusyHandsOverToTheNextOne() {
        // Three attempts on the primary, all E003, then the fallback delivers.
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));
        polls.add(succeeded());

        byte[] image = predictions.run(
                ask("google/nano-banana-pro", "bytedance/seedream-4"), "Render");

        assertThat(image).isEqualTo(IMAGE);
        assertThat(started).containsExactly(
                "google/nano-banana-pro", "google/nano-banana-pro", "google/nano-banana-pro",
                "bytedance/seedream-4");
    }

    @Test
    void aRefusalAboutTheImageStopsTheChainImmediately() {
        // A safety block gets the same answer from every model. Spending the customer's
        // wait proving that four more times is the thing to avoid.
        polls.add(failedWith("NSFW content detected"));

        assertThatThrownBy(() -> predictions.run(
                ask("google/nano-banana-pro", "bytedance/seedream-4"), "Render"))
                .isInstanceOf(ExternalServiceException.class);

        assertThat(started).containsExactly("google/nano-banana-pro");
    }

    @Test
    void everyModelBeingBusyStillEndsAsAFailureSoTheCreditGoesBack() {
        for (int i = 0; i < 6; i++) polls.add(failedWith(E003));

        assertThatThrownBy(() -> predictions.run(
                ask("google/nano-banana-pro", "bytedance/seedream-4"), "Render"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("could not be produced");

        // Three attempts each, and no more.
        assertThat(started).hasSize(6);
    }

    @Test
    void eachModelInTheChainGetsTheImagesUnderItsOwnKey() {
        // A body built for Nano Banana gets a 422 from Flux and looks exactly like
        // "that fallback doesn't work", so the failover builds one body per model.
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));
        polls.add(succeeded());

        predictions.run(ask("google/nano-banana-pro", "black-forest-labs/flux-2-pro"), "Render");

        List<Map<String, Object>> bodies = capturedInputs();
        assertThat(bodies.get(0)).containsEntry("image_input",
                List.of("http://photo", "http://mask-1"));
        assertThat(bodies.get(3)).containsEntry("input_images",
                List.of("http://photo", "http://mask-1"));
        // And the size ask is translated into the family's own units.
        assertThat(bodies.get(0)).containsEntry("resolution", "2K");
        assertThat(bodies.get(3)).containsEntry("resolution", "2 MP");
    }

    @Test
    void theMasksSurviveTheFailoverBecauseEveryFallbackTakesAList() {
        // "Keep the original borders" IS the masks. A fallback that quietly dropped them
        // would produce a render that ignored the setting rather than an obvious failure.
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));
        polls.add(succeeded());

        predictions.run(ask("google/nano-banana-pro", "bytedance/seedream-4"), "Render");

        assertThat(capturedInputs().get(3)).containsEntry("image_input",
                List.of("http://photo", "http://mask-1"));
    }

    @Test
    void anExhaustedBudgetStopsTheChainRatherThanOutlivingTheSweeper() {
        // The sweeper refunds a render that has gone too long without finishing. A retry
        // chain that outlived it would refund a customer whose image then arrives.
        ReflectionTestUtils.setField(predictions, "totalBudgetMs", 30_000L);
        ReflectionTestUtils.setField(predictions, "retryBackoffMs", 60_000L);
        ReflectionTestUtils.setField(predictions, "maxBackoffMs", 60_000L);
        polls.add(failedWith(E003));
        polls.add(failedWith(E003));

        assertThatThrownBy(() -> predictions.run(
                ask("google/nano-banana-pro", "bytedance/seedream-4"), "Render"))
                .isInstanceOf(ExternalServiceException.class);

        // One attempt each: there was never room for a 60s wait inside a 30s budget, so
        // it moved on instead of sleeping past the deadline.
        assertThat(started).containsExactly("google/nano-banana-pro", "bytedance/seedream-4");
    }

    @Test
    void backoffGrowsAndIsNeverTheSameNumberTwiceInARow() {
        ReflectionTestUtils.setField(predictions, "retryBackoffMs", 1000L);
        ReflectionTestUtils.setField(predictions, "maxBackoffMs", 8000L);

        // Equal jitter: half the computed wait, plus a random amount up to the other half.
        assertThat(predictions.backoffMs(1)).isBetween(500L, 1000L);
        assertThat(predictions.backoffMs(2)).isBetween(1000L, 2000L);
        assertThat(predictions.backoffMs(3)).isBetween(2000L, 4000L);
        // Capped, so a long chain cannot wander past the budget on one wait.
        assertThat(predictions.backoffMs(9)).isBetween(4000L, 8000L);

        // The jitter is the part that stops sixteen threads re-asking a busy model at the
        // same instant, so it has to actually vary.
        assertThat(List.of(
                predictions.backoffMs(3), predictions.backoffMs(3), predictions.backoffMs(3),
                predictions.backoffMs(3), predictions.backoffMs(3), predictions.backoffMs(3)))
                .doesNotHaveDuplicates();
    }

    @Test
    void aModelThisDeploymentCannotCallIsSkippedRatherThanTried() {
        // openai/* needs an OpenAI key as an input; without one the call is a wasted
        // minute of the budget proving it cannot work.
        polls.add(succeeded());

        predictions.run(ask("openai/gpt-image-1", "google/nano-banana-pro"), "Render");

        assertThat(started).containsExactly("google/nano-banana-pro");
    }

    @Test
    void anEmptyChainIsReportedRatherThanSilentlyDoingNothing() {
        assertThatThrownBy(() -> predictions.run(ask("openai/gpt-image-1"), "Render"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("no model configured");
    }

    /** The {@code input} map of each started prediction, in order. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedInputs() {
        org.mockito.ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                .exchange(any(String.class), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        return captor.getAllValues().stream()
                .map(entity -> (Map<String, Object>) entity.getBody().get("input"))
                .toList();
    }
}
