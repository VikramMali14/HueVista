package com.gridstore.huevista.image.service;

import com.gridstore.huevista.image.model.ImageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the scene out of the classifier's reply.
 *
 * <p>The stakes here are higher than a one-word parse suggests: a reply this code
 * cannot read becomes INVALID, and INVALID rejects the upload with "please upload a
 * photo of a room or a house". The exact-match switch this replaced turned a trailing
 * full stop into exactly that refusal.
 */
class ClaudeVisionParseTest {

    @ParameterizedTest
    @ValueSource(strings = {"OUTDOOR", "outdoor", "Outdoor", "OUTDOOR.", " OUTDOOR \n",
            "**OUTDOOR**", "The image is OUTDOOR"})
    void findsTheVerdictHoweverTheModelDressesItUp(String answer) {
        assertThat(ClaudeVisionService.parseAnswer(answer)).isEqualTo(ImageType.OUTDOOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INDOOR", "indoor.", "This is an INDOOR room"})
    void readsIndoorTheSameWay(String answer) {
        assertThat(ClaudeVisionService.parseAnswer(answer)).isEqualTo(ImageType.INDOOR);
    }

    @Test
    void doesNotFindIndoorInsideTheWordOutdoor() {
        // A substring search would call every single outdoor photo indoor — "OUTDOOR"
        // contains "TDOOR", and a sloppy contains("INDOOR") check on "outdoors and
        // indoor-style" would flip on word order alone.
        assertThat(ClaudeVisionService.parseAnswer("OUTDOOR")).isEqualTo(ImageType.OUTDOOR);
        assertThat(ClaudeVisionService.parseAnswer("OUTDOORS")).isNull();
    }

    @Test
    void takesTheFirstVerdictWhenTheModelQualifiesItself() {
        // Models lead with the answer and hedge afterwards.
        assertThat(ClaudeVisionService.parseAnswer(
                "OUTDOOR, though the balcony could read as indoor"))
                .isEqualTo(ImageType.OUTDOOR);
        assertThat(ClaudeVisionService.parseAnswer(
                "INDOOR — the window shows an outdoor view"))
                .isEqualTo(ImageType.INDOOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID", "invalid.", "I can't tell what this is", ""})
    void anythingThatNamesNeitherIsNoAnswerAtAll(String answer) {
        assertThat(ClaudeVisionService.parseAnswer(answer)).isNull();
    }

    @Test
    void aNullReplyIsNoAnswerRatherThanACrash() {
        assertThat(ClaudeVisionService.parseAnswer(null)).isNull();
    }
}
