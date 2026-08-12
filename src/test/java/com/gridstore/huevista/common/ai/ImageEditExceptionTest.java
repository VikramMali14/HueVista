package com.gridstore.huevista.common.ai;

import com.gridstore.huevista.common.ai.ImageEditException.Disposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classifier decides whether a failed clean is retried, failed over, or dropped, so
 * these tests are written against error strings the providers have actually returned
 * rather than invented ones.
 */
class ImageEditExceptionTest {

    /** The exact text Replicate returned when Nano Banana Pro had no capacity. */
    private static final String E003 = "Prediction failed: Async prediction failed: "
            + "ModelRateLimitError: Service is currently unavailable due to high demand. "
            + "Please try again later. (E003) (1cah9wlWR9)";

    @Test
    void treatsTheRateLimitThatBrokeTheCleanAsWorthRetrying() {
        assertThat(ImageEditException.classify(E003)).isEqualTo(Disposition.RETRY);

        ImageEditException e = new ImageEditException(E003, ImageEditException.classify(E003));
        assertThat(e.retryable()).isTrue();
        assertThat(e.worthFailingOver()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ModelRateLimitError (E003)",
            "429 Too Many Requests",
            "RESOURCE_EXHAUSTED: quota exceeded for this project",
            "The model is overloaded, try again later",
            "Service unavailable",
    })
    void busyMeansAskAgain(String error) {
        assertThat(ImageEditException.classify(error)).isEqualTo(Disposition.RETRY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "NSFW content detected",
            "Blocked by the content policy",
            "IMAGE_SAFETY",
            "INVALID_ARGUMENT: image too large",
    })
    void arefusalAboutTheImageIsNotRetriedAnywhere(String error) {
        Disposition d = ImageEditException.classify(error);
        assertThat(d).isEqualTo(Disposition.GIVE_UP);
        assertThat(new ImageEditException(error, d).worthFailingOver()).isFalse();
    }

    @Test
    void anUnrecognisedErrorFailsOverRatherThanGivingUp() {
        // Being wrong this way costs one call to the other provider. Being wrong the
        // other way costs the user their clean, so the tie is broken deliberately.
        Disposition d = ImageEditException.classify("something nobody has seen before");
        assertThat(d).isEqualTo(Disposition.FAILOVER);
        assertThat(new ImageEditException("x", d).retryable()).isFalse();
        assertThat(new ImageEditException("x", d).worthFailingOver()).isTrue();
    }

    @Test
    void missingErrorTextStillGetsTheOtherProviderATurn() {
        assertThat(ImageEditException.classify(null)).isEqualTo(Disposition.FAILOVER);
        assertThat(ImageEditException.classify("  ")).isEqualTo(Disposition.FAILOVER);
    }

    @Test
    void aRefusalWinsOverAWordThatMerelySoundsBusy() {
        // "temporarily unavailable" reads retryable, but a safety block is the reason
        // and no amount of retrying will change it.
        assertThat(ImageEditException.classify(
                "Blocked for safety; the model is temporarily unavailable for this prompt"))
                .isEqualTo(Disposition.GIVE_UP);
    }

    @Test
    void factoriesCarryTheirDisposition() {
        assertThat(ImageEditException.retry("x").disposition()).isEqualTo(Disposition.RETRY);
        assertThat(ImageEditException.failover("x").disposition()).isEqualTo(Disposition.FAILOVER);
        assertThat(ImageEditException.giveUp("x").disposition()).isEqualTo(Disposition.GIVE_UP);
    }
}
