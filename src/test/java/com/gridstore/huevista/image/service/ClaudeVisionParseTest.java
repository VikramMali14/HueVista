package com.gridstore.huevista.image.service;

import com.gridstore.huevista.image.model.HouseType;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.SceneAnalysis;
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

    // ── The richer analysis ──────────────────────────────────────────────────
    //
    // Same stakes, differently distributed. The scene still routes the whole pipeline,
    // so nothing about a bad house type or a mangled hex may be allowed to cost us it —
    // which is why almost every test below is about one field failing WITHOUT taking the
    // others with it.

    @Test
    void readsAWholeAnalysisOutOfCleanJson() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis("""
                {"scene":"INDOOR","type":"BATHROOM","wallHex":"#E8D5B0",
                 "wallColour":"pale cream","trimHex":"#4A362A"}""");

        assertThat(a.scene()).isEqualTo(ImageType.INDOOR);
        assertThat(a.houseType()).isEqualTo(HouseType.BATHROOM);
        assertThat(a.wallHex()).isEqualTo("#e8d5b0");   // normalised to lower case
        assertThat(a.wallColourName()).isEqualTo("pale cream");
        assertThat(a.trimHex()).isEqualTo("#4a362a");
        assertThat(a.hasWallColour()).isTrue();
    }

    @Test
    void readsItThroughTheCodeFencesModelsKeepAddingAnyway() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(
                "```json\n{\"scene\":\"OUTDOOR\",\"type\":\"SHOPFRONT\"}\n```");

        assertThat(a.scene()).isEqualTo(ImageType.OUTDOOR);
        assertThat(a.houseType()).isEqualTo(HouseType.SHOPFRONT);
    }

    /**
     * The fallback that matters most: a model that ignored the format but answered the
     * question still routes the photo. Losing the scene because the JSON was malformed
     * would fail a run over a missing brace.
     */
    @Test
    void aReplyThatIsNotJsonStillYieldsTheScene() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(
                "This is an INDOOR bathroom, probably. Sorry, I can't do JSON.");

        assertThat(a.scene()).isEqualTo(ImageType.INDOOR);
        assertThat(a.houseType()).isEqualTo(HouseType.UNKNOWN);
        assertThat(a.wallHex()).isNull();
    }

    @Test
    void aNullReplyIsAnEmptyAnalysisRatherThanACrash() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(null);

        assertThat(a.scene()).isNull();
        assertThat(a.houseType()).isEqualTo(HouseType.UNKNOWN);
        assertThat(a.hasWallColour()).isFalse();
    }

    /**
     * The model contradicting itself. The scene is what four downstream decisions
     * already branch on, so it wins the argument and the type simply stops contributing
     * a clause — rather than a bathroom's tile rules being applied to a facade.
     */
    @Test
    void aTypeThatContradictsItsOwnSceneIsDropped() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(
                "{\"scene\":\"OUTDOOR\",\"type\":\"BEDROOM\"}");

        assertThat(a.scene()).isEqualTo(ImageType.OUTDOOR);
        assertThat(a.houseType()).isEqualTo(HouseType.UNKNOWN);
    }

    @Test
    void anInventedTypeBecomesUnknownWithoutCostingTheScene() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(
                "{\"scene\":\"INDOOR\",\"type\":\"CONSERVATORY\"}");

        assertThat(a.scene()).isEqualTo(ImageType.INDOOR);
        assertThat(a.houseType()).isEqualTo(HouseType.UNKNOWN);
    }

    /**
     * A colour name with no swatch beside it is worse than neither: the studio would
     * print "faded terracotta" next to nothing at all. So the name goes with the hex.
     */
    @Test
    void anUnreadableHexTakesItsColourNameWithIt() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(
                "{\"scene\":\"INDOOR\",\"wallHex\":\"beige-ish\",\"wallColour\":\"warm beige\"}");

        assertThat(a.scene()).isEqualTo(ImageType.INDOOR);
        assertThat(a.wallHex()).isNull();
        assertThat(a.wallColourName()).isNull();
    }

    /** Declining to guess is the WANTED answer for an unpainted wall, not a failure. */
    @Test
    void anExplicitNullColourIsRespected() {
        SceneAnalysis a = ClaudeVisionService.parseAnalysis(
                "{\"scene\":\"INDOOR\",\"type\":\"BEDROOM\",\"wallHex\":null,\"wallColour\":null}");

        assertThat(a.houseType()).isEqualTo(HouseType.BEDROOM);
        assertThat(a.hasWallColour()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#E8D5B0", "e8d5b0", " #e8d5b0 ", "#e8D5b0"})
    void hexesAreAcceptedHoweverTheModelWritesThem(String raw) {
        assertThat(ClaudeVisionService.normaliseHex(raw)).isEqualTo("#e8d5b0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"#fff", "#e8d5b", "#e8d5b0f", "rgb(232,213,176)", "beige",
            "#gggggg", "", "  "})
    void anythingThatIsNotSixHexDigitsIsNoColour(String raw) {
        // Strict rather than forgiving: this value is rendered as a swatch beside
        // catalogue shades, and every consumer downstream assumes six hex digits.
        assertThat(ClaudeVisionService.normaliseHex(raw)).isNull();
    }

    @Test
    void jsonThatIsNotAnObjectFallsBackToReadingTheScene() {
        assertThat(ClaudeVisionService.parseAnalysis("\"OUTDOOR\"").scene())
                .isEqualTo(ImageType.OUTDOOR);
        assertThat(ClaudeVisionService.parseAnalysis("[1,2,3]").scene()).isNull();
    }
}
