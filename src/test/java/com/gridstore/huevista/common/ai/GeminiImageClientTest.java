package com.gridstore.huevista.common.ai;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the pure parts of {@link GeminiImageClient} — no HTTP involved. */
class GeminiImageClientTest {

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ── Aspect ratio ────────────────────────────────────────────────────────

    @Test
    void picksTheClosestBucketForARealUpload() throws Exception {
        // 1478x860 — the photo from the run that first hit the rate limit. 1.72 is much
        // nearer 16:9 (1.78) than 3:2 (1.50), and getting this wrong would stretch the
        // house and misplace every mask drawn over it.
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1478, 860))).isEqualTo("16:9");
    }

    @Test
    void mapsTheOrdinaryShapesToThemselves() throws Exception {
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1000, 1000))).isEqualTo("1:1");
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1920, 1080))).isEqualTo("16:9");
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1080, 1920))).isEqualTo("9:16");
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1200, 800))).isEqualTo("3:2");
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(800, 1200))).isEqualTo("2:3");
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1024, 768))).isEqualTo("4:3");
    }

    @Test
    void isSymmetricBetweenLandscapeAndPortrait() throws Exception {
        // Measured in log space, so a 3:1 panorama is as far from 16:9 as a 1:3 tower is
        // from 9:16 — a plain ratio difference would quietly favour landscape.
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(3000, 1000))).isEqualTo("21:9");
        assertThat(GeminiImageClient.nearestAspectRatio(jpeg(1000, 3000))).isEqualTo("9:16");
    }

    @Test
    void returnsNoRatioWhenTheBytesAreNotAnImage() {
        // Null means "let the model choose", which beats asserting a wrong aspect.
        assertThat(GeminiImageClient.nearestAspectRatio("not an image".getBytes())).isNull();
        assertThat(GeminiImageClient.nearestAspectRatio(new byte[0])).isNull();
        assertThat(GeminiImageClient.nearestAspectRatio(null)).isNull();
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private static Map<String, Object> responseWith(String inlineKey, String data) {
        return Map.of("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(
                        Map.of("text", "Here is the cleaned room."),
                        Map.of(inlineKey, Map.of("mimeType", "image/png", "data", data)))))));
    }

    @Test
    void readsTheImageBackUnderEitherSpellingOfTheKey() {
        String data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        // Requests go up as inline_data and answers come back as inlineData; that
        // asymmetry is exactly the sort of thing that changes without notice.
        assertThat(GeminiImageClient.firstInlineImage(responseWith("inlineData", data)))
                .containsExactly(1, 2, 3, 4);
        assertThat(GeminiImageClient.firstInlineImage(responseWith("inline_data", data)))
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void skipsThePartsThatAreJustTheModelNarrating() {
        String data = Base64.getEncoder().encodeToString(new byte[]{9});
        assertThat(GeminiImageClient.firstInlineImage(responseWith("inlineData", data)))
                .containsExactly(9);
    }

    @Test
    void returnsNothingWhenThereIsNoImagePart() {
        assertThat(GeminiImageClient.firstInlineImage(null)).isNull();
        assertThat(GeminiImageClient.firstInlineImage(Map.of())).isNull();
        assertThat(GeminiImageClient.firstInlineImage(Map.of("candidates", List.of()))).isNull();
        assertThat(GeminiImageClient.firstInlineImage(Map.of("candidates", List.of(
                Map.of("content", Map.of("parts", List.of(Map.of("text", "I can't do that."))))))))
                .isNull();
    }

    @Test
    void namesWhyTheModelProducedNothing() {
        assertThat(GeminiImageClient.blockReason(
                Map.of("promptFeedback", Map.of("blockReason", "SAFETY"))))
                .isEqualTo("SAFETY");
        assertThat(GeminiImageClient.blockReason(
                Map.of("candidates", List.of(Map.of("finishReason", "IMAGE_SAFETY")))))
                .isEqualTo("IMAGE_SAFETY");
    }

    @Test
    void aNormalFinishIsNotABlock() {
        assertThat(GeminiImageClient.blockReason(
                Map.of("candidates", List.of(Map.of("finishReason", "STOP"))))).isNull();
        assertThat(GeminiImageClient.blockReason(Map.of())).isNull();
        assertThat(GeminiImageClient.blockReason(null)).isNull();
    }

    // ── MIME sniffing ───────────────────────────────────────────────────────

    @Test
    void declaresTheFormatTheBytesActuallyAre() throws Exception {
        // The API rejects a wrong content type rather than working it out itself, and
        // uploads arrive as either.
        assertThat(GeminiImageClient.sniffMimeType(png(4, 4))).isEqualTo("image/png");
        assertThat(GeminiImageClient.sniffMimeType(jpeg(4, 4))).isEqualTo("image/jpeg");
    }
}
