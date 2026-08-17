package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.ai.AiModelCatalogue;
import com.gridstore.huevista.common.ai.GeminiImageClient;
import com.gridstore.huevista.common.ai.ImageEditException;
import com.gridstore.huevista.common.ai.ReplicateAuthException;
import com.gridstore.huevista.common.ai.ReplicateImageEditor;
import com.gridstore.huevista.image.model.ImageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cleaning chain: FLUX 2 Pro, Nano Banana 2, FLUX 2 Max, Nano Banana Pro — one
 * attempt each, until one of them produces an image.
 *
 * <p>This matters more than it reads. Wall detection runs ONLY against a cleaned canvas,
 * so a clean that nobody serves is not a degraded run — it is no run at all.
 *
 * <p>Two properties are worth stating because they are what the order buys. The chain
 * ALTERNATES families, so whatever is asked after a failure has a different queue behind
 * it; and each model gets ONE try, because the thing that fails here is almost always
 * that queue, and a queue that is full stays full for longer than a retry takes.
 */
class ImageCleanerFallbackTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};

    private final CleaningHintService hints = mock(CleaningHintService.class);
    private final GeminiImageClient gemini = mock(GeminiImageClient.class);
    private final ReplicateImageEditor replicate = mock(ReplicateImageEditor.class);
    private final RestTemplate rest = mock(RestTemplate.class);
    /** Real, not a mock: it is a pure lookup, and the progress notes read better in a
     *  failure message when they carry the labels production would actually show. */
    private final AiModelCatalogue catalogue = new AiModelCatalogue();
    private final ImageCleanerService cleaner = new ImageCleanerService(
            rest, hints, gemini, replicate, catalogue);

    /** Google takes the photo's BYTES, so the chain downloads the original first. */
    private void photoIsDownloadable() {
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(IMAGE));
    }

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(cleaner, "enabled", true);
        ReflectionTestUtils.setField(cleaner, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(cleaner, "model", "black-forest-labs/flux-2-pro");
        ReflectionTestUtils.setField(cleaner, "fallbackModels",
                "google/nano-banana-2,black-forest-labs/flux-2-max,google/nano-banana-pro");
        ReflectionTestUtils.setField(cleaner, "maxAttempts", 1);
        ReflectionTestUtils.setField(cleaner, "retryBackoffMs", 0L);
        ReflectionTestUtils.setField(cleaner, "upscaleLongestPx", 0);
        when(replicate.canRun(anyString())).thenReturn(true);
    }

    @Test
    void theFirstModelThatDeliversEndsTheChain() {
        when(replicate.edit(any())).thenReturn(IMAGE);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        verify(replicate, times(1)).edit(any());
        // Nobody else is asked — and in particular nobody else is BILLED.
        verify(gemini, never()).edit(anyString(), any(), any());
    }

    @Test
    void aBusyModelHandsStraightToTheNextFamilyRatherThanRetryingItself() {
        // The E003 case: the model is fine, Replicate's pool for it is full. A second go
        // at the same pool is a minute spent learning the same thing, so the chain moves
        // to the other family immediately.
        when(replicate.edit(any()))
                .thenThrow(ImageEditException.retry(
                        "ModelRateLimitError: Service is currently unavailable due to high demand (E003)"))
                .thenReturn(IMAGE);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        ArgumentCaptor<ReplicateImageEditor.Spec> specs =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate, times(2)).edit(specs.capture());
        assertThat(specs.getAllValues()).extracting(ReplicateImageEditor.Spec::model)
                .containsExactly("black-forest-labs/flux-2-pro", "google/nano-banana-2");
        // Google's own API is a tail step and off by default — it is not what a busy
        // Replicate model falls over to any more.
        verify(gemini, never()).edit(anyString(), any(), any());
    }

    @Test
    void theChainIsWalkedInTheConfiguredOrderUntilOneDelivers() {
        when(replicate.edit(any()))
                .thenThrow(ImageEditException.failover("flux 2 pro produced nothing"))
                .thenThrow(ImageEditException.failover("nano banana 2 produced nothing"))
                .thenReturn(IMAGE); // FLUX 2 Max delivers

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        ArgumentCaptor<ReplicateImageEditor.Spec> specs =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate, times(3)).edit(specs.capture());
        assertThat(specs.getAllValues()).extracting(ReplicateImageEditor.Spec::model)
                .containsExactly("black-forest-labs/flux-2-pro",
                        "google/nano-banana-2",
                        "black-forest-labs/flux-2-max");
        // Nano Banana Pro is never reached: the chain stops at the first image, it does
        // not work through the whole list.
    }

    @Test
    void everyLinkGetsExactlyOneTry() {
        // The property the order depends on. Two tries per model would spend eight model
        // calls and most of the run's deadline before the chain had visited both families.
        when(replicate.edit(any())).thenThrow(ImageEditException.retry("busy"));

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).isEmpty();

        ArgumentCaptor<ReplicateImageEditor.Spec> specs =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate, times(4)).edit(specs.capture());
        assertThat(specs.getAllValues()).extracting(ReplicateImageEditor.Spec::model)
                .containsExactly("black-forest-labs/flux-2-pro",
                        "google/nano-banana-2",
                        "black-forest-labs/flux-2-max",
                        "google/nano-banana-pro");
    }

    @Test
    void theRunNarratesItselfSoTheStudioCanSayWhatIsHappening() {
        // A four-model chain can take minutes. Without this the studio shows one
        // unchanging spinner, a working run looks identical to a dead one, and the
        // rational thing to do about a dead page is close it — which loses the work.
        when(replicate.edit(any()))
                .thenThrow(ImageEditException.retry("busy"))
                .thenReturn(IMAGE);
        List<String> notes = new ArrayList<>();

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR, null, notes::add))
                .contains(IMAGE);

        // Said BEFORE each call, so the sentence is on screen for the minute the model
        // is thinking rather than after it has answered.
        assertThat(notes).hasSize(2);
        assertThat(notes.get(0)).contains("FLUX 2 Pro");
        assertThat(notes.get(1))
                .contains("busy")
                .contains("Nano Banana 2")
                .contains("2 of 4");
    }

    @Test
    void aModelThatCannotRunIsSkippedRatherThanCalledAndFailed() {
        // GPT Image needs the caller's OpenAI key. Without it the model exists but the
        // request cannot be built, and spending a minute proving that would eat the
        // run's deadline for nothing.
        ReflectionTestUtils.setField(cleaner, "fallbackModels",
                "openai/gpt-image-1,google/nano-banana-2");
        when(replicate.canRun("openai/gpt-image-1")).thenReturn(false);
        when(replicate.edit(any()))
                .thenThrow(ImageEditException.failover("flux 2 pro produced nothing"))
                .thenReturn(IMAGE); // nano banana 2 delivers

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        ArgumentCaptor<ReplicateImageEditor.Spec> specs =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate, times(2)).edit(specs.capture());
        assertThat(specs.getAllValues()).extracting(ReplicateImageEditor.Spec::model)
                .containsExactly("black-forest-labs/flux-2-pro", "google/nano-banana-2");
    }

    @Test
    void aRefusalAboutThePhotoStopsTheWholeChain() {
        // A safety block is a verdict on the image. Every other model would return the
        // same verdict, and four of them saying so takes four minutes off the run.
        when(replicate.edit(any())).thenThrow(
                ImageEditException.giveUp("prediction failed: blocked by the content policy"));
        when(gemini.isConfigured()).thenReturn(true);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).isEmpty();

        verify(replicate, times(1)).edit(any());
        verify(gemini, never()).edit(anyString(), any(), any());
    }

    @Test
    void aDeadReplicateTokenSkipsTheRestOfTheChainButStillAsksGoogle() {
        // 401/403 is the account, not the model: asking three more Replicate models
        // buys the same refusal three more times. Google is a different account, so the
        // tail step is the one thing still worth trying — when it is switched on.
        ReflectionTestUtils.setField(cleaner, "geminiFallback", true);
        when(replicate.edit(any())).thenThrow(new ReplicateAuthException("Replicate rejected our API token (401)"));
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3-pro-image-preview");
        when(gemini.edit(anyString(), any(), any())).thenReturn(IMAGE);
        photoIsDownloadable();

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        verify(replicate, times(1)).edit(any());
        verify(gemini).edit(anyString(), any(), any());
    }

    @Test
    void googlesOwnApiIsOffUnlessTheDeploymentAsksForIt() {
        // The chain already reaches Gemini twice through Replicate. A fifth provider
        // nobody listed would make "every model we ask turned it down" mean something
        // different from what the chain says, so it is opt-in.
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("nope"));
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3-pro-image-preview");
        photoIsDownloadable();

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).isEmpty();

        verify(replicate, times(4)).edit(any());
        verify(gemini, never()).edit(anyString(), any(), any());
    }

    @Test
    void nothingInTheChainDeliveringIsAnEmptyResultRatherThanAnException() {
        // The caller (SegmentationService) turns this into a FAILED run carrying
        // SYSTEM_UNDER_LOAD — it must not be an exception thrown through the worker.
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("nope"));
        when(gemini.isConfigured()).thenReturn(false);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).isEmpty();
        verify(replicate, times(4)).edit(any()); // the whole chain, one try each
    }

    @Test
    void theConfiguredListIsCleanedUpAndNeverRepeatsTheFirstModel() {
        ReflectionTestUtils.setField(cleaner, "fallbackModels",
                " google/nano-banana-2 , black-forest-labs/flux-2-pro ,, google/nano-banana-pro ");

        assertThat(cleaner.fallbackModelList())
                .containsExactly("google/nano-banana-2", "google/nano-banana-pro");
        assertThat(cleaner.modelChain())
                .containsExactly("black-forest-labs/flux-2-pro",
                        "google/nano-banana-2",
                        "google/nano-banana-pro");
    }

    @Test
    void anEmptyFallbackListTurnsTheExtraProvidersOff() {
        ReflectionTestUtils.setField(cleaner, "fallbackModels", "");
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("nope"));
        when(gemini.isConfigured()).thenReturn(false);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).isEmpty();
        assertThat(cleaner.fallbackModelList()).isEqualTo(List.of());
        verify(replicate, times(1)).edit(any());
    }

    @Test
    void isAvailableTracksWhetherAnyProviderCouldServeAClean() {
        assertThat(cleaner.isAvailable()).isTrue();

        // No Replicate token, but Google is configured — still available.
        ReflectionTestUtils.setField(cleaner, "replicateApiToken", "");
        when(gemini.isConfigured()).thenReturn(true);
        assertThat(cleaner.isAvailable()).isTrue();

        // Neither: the clean was never going to run, so the gate downstream must NOT
        // treat its absence as a failure.
        when(gemini.isConfigured()).thenReturn(false);
        assertThat(cleaner.isAvailable()).isFalse();

        // Kill switch beats everything.
        ReflectionTestUtils.setField(cleaner, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(cleaner, "enabled", false);
        assertThat(cleaner.isAvailable()).isFalse();
    }

    @Test
    void anAdminOverrideReplacesTheWholeChain() {
        when(replicate.edit(any())).thenReturn(IMAGE);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR,
                "black-forest-labs/flux-2-max")).contains(IMAGE);

        ArgumentCaptor<ReplicateImageEditor.Spec> spec =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate).edit(spec.capture());
        assertThat(spec.getValue().model()).isEqualTo("black-forest-labs/flux-2-max");
    }

    @Test
    void anOverriddenRunAsksThatModelAndNobodyElse() {
        // The point of the override is a comparison. A clean quietly served by Gemini or
        // by FLUX would look exactly like one served by the model under test, and the
        // admin would attribute the image to the wrong model — worse than no image.
        ReflectionTestUtils.setField(cleaner, "geminiFallback", true);
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("seedream declined"));
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3-pro-image-preview");
        when(gemini.edit(anyString(), any(), any())).thenReturn(IMAGE);
        photoIsDownloadable();

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR,
                "bytedance/seedream-4")).isEmpty();

        verify(replicate, times(1)).edit(any());
        verify(gemini, never()).edit(anyString(), any(), any());
    }

    @Test
    void overridingWithTheConfiguredModelStillPinsTheRunToIt() {
        // Picking "FLUX 2 Pro" from the radio asks for that one model, even though it is
        // also what the config says. Carrying on down the chain here would answer a
        // different question from the one the admin asked.
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("busy"));
        when(gemini.isConfigured()).thenReturn(false);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR,
                "black-forest-labs/flux-2-pro")).isEmpty();

        verify(replicate, times(1)).edit(any());
    }

    @Test
    void aBlankOverrideIsTheOrdinaryRun() {
        // Null and "" both mean "nothing was pinned" — the studio sends the empty string
        // when the admin puts the radio back on "the configured model".
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("nope"));
        when(gemini.isConfigured()).thenReturn(false);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR, "  ")).isEmpty();

        verify(replicate, times(4)).edit(any()); // the whole chain, one try each
    }

    @Test
    void theCleanIsAskedForWithTheSceneItWasGiven() {
        when(replicate.edit(any())).thenReturn(IMAGE);
        when(hints.describeCleanup(anyString(), any())).thenReturn(Optional.empty());

        cleaner.cleanImage("http://photo", ImageType.INDOOR);

        ArgumentCaptor<ReplicateImageEditor.Spec> spec =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate).edit(spec.capture());
        // The interior prompt, not the exterior one — the ceiling/floor finishing rules
        // only exist in the indoor variant.
        assertThat(spec.getValue().prompt())
                .isEqualTo(ImageCleanerService.cleanPromptFor(ImageType.INDOOR));
    }
}
