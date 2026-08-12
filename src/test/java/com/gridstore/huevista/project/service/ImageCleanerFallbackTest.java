package com.gridstore.huevista.project.service;

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
 * The cleaning hierarchy: Nano Banana Pro, then the same model through Google, then a
 * different model family, until one of them produces an image.
 *
 * <p>This matters more than it reads. Wall detection now runs ONLY against a cleaned
 * canvas, so a clean that nobody serves is not a degraded run — it is no run at all.
 */
class ImageCleanerFallbackTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};

    private final CleaningHintService hints = mock(CleaningHintService.class);
    private final GeminiImageClient gemini = mock(GeminiImageClient.class);
    private final ReplicateImageEditor replicate = mock(ReplicateImageEditor.class);
    private final RestTemplate rest = mock(RestTemplate.class);
    private final ImageCleanerService cleaner = new ImageCleanerService(
            rest, hints, gemini, replicate);

    /** Google takes the photo's BYTES, so the chain downloads the original first. */
    private void photoIsDownloadable() {
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(IMAGE));
    }

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(cleaner, "enabled", true);
        ReflectionTestUtils.setField(cleaner, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(cleaner, "model", "google/nano-banana-pro");
        ReflectionTestUtils.setField(cleaner, "fallbackModels",
                "black-forest-labs/flux-2-pro,openai/gpt-image-1,bytedance/seedream-4");
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
    void aBusyReplicateFallsOverToGoogleBeforeTryingADifferentModel() {
        // The E003 case: the model is fine, Replicate's pool is full. The same model
        // through Google's own queue is the cheapest possible failover, and it produces
        // an identical canvas — so it is asked BEFORE any different model.
        when(replicate.edit(any())).thenThrow(ImageEditException.retry(
                "ModelRateLimitError: Service is currently unavailable due to high demand (E003)"));
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3-pro-image-preview");
        when(gemini.edit(anyString(), any(), any())).thenReturn(IMAGE);
        photoIsDownloadable();

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        verify(replicate, times(1)).edit(any());
        verify(gemini).edit(anyString(), any(), any());
    }

    @Test
    void everyGeminiRouteFailingWalksTheConfiguredHierarchyInOrder() {
        when(replicate.edit(any()))
                .thenThrow(ImageEditException.failover("nano banana produced nothing"))
                .thenThrow(ImageEditException.failover("flux produced nothing"))
                .thenReturn(IMAGE); // GPT Image delivers
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.model()).thenReturn("gemini-3-pro-image-preview");
        when(gemini.edit(anyString(), any(), any()))
                .thenThrow(ImageEditException.failover("google declined too"));
        photoIsDownloadable();

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        ArgumentCaptor<ReplicateImageEditor.Spec> specs =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate, times(3)).edit(specs.capture());
        assertThat(specs.getAllValues()).extracting(ReplicateImageEditor.Spec::model)
                .containsExactly("google/nano-banana-pro",
                        "black-forest-labs/flux-2-pro",
                        "openai/gpt-image-1");
        // Seedream is never reached: the chain stops at the first image, it doesn't
        // work through the list.
    }

    @Test
    void aProviderThatCannotRunIsSkippedRatherThanCalledAndFailed() {
        // GPT Image needs the caller's OpenAI key. Without it the model exists but the
        // request cannot be built, and spending a minute proving that would eat the
        // run's deadline for nothing.
        when(replicate.canRun("openai/gpt-image-1")).thenReturn(false);
        when(replicate.edit(any()))
                .thenThrow(ImageEditException.failover("nano banana produced nothing"))
                .thenThrow(ImageEditException.failover("flux produced nothing"))
                .thenReturn(IMAGE); // seedream delivers

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).contains(IMAGE);

        ArgumentCaptor<ReplicateImageEditor.Spec> specs =
                ArgumentCaptor.forClass(ReplicateImageEditor.Spec.class);
        verify(replicate, times(3)).edit(specs.capture());
        assertThat(specs.getAllValues()).extracting(ReplicateImageEditor.Spec::model)
                .containsExactly("google/nano-banana-pro",
                        "black-forest-labs/flux-2-pro",
                        "bytedance/seedream-4");
    }

    @Test
    void aRefusalAboutThePhotoStopsTheWholeHierarchy() {
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
    void aDeadReplicateTokenSkipsTheRestOfReplicateButStillAsksGoogle() {
        // 401/403 is the account, not the model: asking three more Replicate models
        // buys the same refusal three more times. Google is a different account.
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
    void nothingInTheHierarchyDeliveringIsAnEmptyResultRatherThanAnException() {
        // The caller (SegmentationService) turns this into a FAILED run with a reason
        // the user can report — it must not be an exception thrown through the worker.
        when(replicate.edit(any())).thenThrow(ImageEditException.failover("nope"));
        when(gemini.isConfigured()).thenReturn(false);

        assertThat(cleaner.cleanImage("http://photo", ImageType.OUTDOOR)).isEmpty();
        verify(replicate, times(4)).edit(any()); // primary + three fallbacks
    }

    @Test
    void theConfiguredListIsCleanedUpAndNeverRepeatsThePrimary() {
        ReflectionTestUtils.setField(cleaner, "fallbackModels",
                " black-forest-labs/flux-2-pro , google/nano-banana-pro ,, bytedance/seedream-4 ");

        assertThat(cleaner.fallbackModelList())
                .containsExactly("black-forest-labs/flux-2-pro", "bytedance/seedream-4");
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
