package com.gridstore.huevista.common.ai;

import com.gridstore.huevista.common.ai.ReplicateImageEditor.Family;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The part of the fallback chain that is easy to get silently wrong: every model in it
 * takes the photo under a different key, so a body built for one model gets a 422 from
 * the next and looks exactly like "that fallback doesn't work".
 */
class ReplicateImageEditorTest {

    private final ReplicateImageEditor editor = new ReplicateImageEditor(mock(RestTemplate.class));

    @ParameterizedTest
    @CsvSource({
            "google/nano-banana-pro,        NANO_BANANA",
            "google/nano-banana-2,          NANO_BANANA",
            "black-forest-labs/flux-2-pro,  FLUX",
            "black-forest-labs/flux-2-max,  FLUX",
            "black-forest-labs/flux-kontext-pro, FLUX_KONTEXT",
            "openai/gpt-image-1,            OPENAI",
            "openai/gpt-image-1-mini,       OPENAI",
            "bytedance/seedream-4,          SEEDREAM",
            "bytedance/seedream-4-5,        SEEDREAM",
    })
    void recognisesEachFamilyFromTheModelIdAlone(String model, Family expected) {
        // Newer tiers of a family must resolve without a code change — that is the
        // whole reason the chain lives in configuration.
        assertThat(ReplicateImageEditor.familyOf(model)).isEqualTo(expected);
    }

    @Test
    void anUnknownModelIsTreatedAsTheCommonestSchemaRatherThanRejected() {
        assertThat(ReplicateImageEditor.familyOf("some-lab/brand-new-editor"))
                .isEqualTo(Family.NANO_BANANA);
    }

    @Test
    void eachFamilyGetsThePhotoUnderTheKeyItActuallyReads() {
        assertThat(buildInput("google/nano-banana-pro")).containsEntry(
                "image_input", List.of("http://photo"));
        assertThat(buildInput("black-forest-labs/flux-2-pro")).containsEntry(
                "input_images", List.of("http://photo"));
        assertThat(buildInput("openai/gpt-image-1")).containsEntry(
                "input_images", List.of("http://photo"));
        assertThat(buildInput("bytedance/seedream-4")).containsEntry(
                "image_input", List.of("http://photo"));
        // Kontext edits exactly ONE image and takes it as a bare string, not a list.
        assertThat(buildInput("black-forest-labs/flux-kontext-pro")).containsEntry(
                "input_image", "http://photo");
    }

    @Test
    void sizeIsAskedForInEachFamilysOwnUnits() {
        // The config is written once, in Nano Banana's 1K/2K/4K.
        assertThat(buildInput("google/nano-banana-pro")).containsEntry("resolution", "1K");
        assertThat(buildInput("black-forest-labs/flux-2-pro")).containsEntry("resolution", "1 MP");
        assertThat(buildInput("bytedance/seedream-4")).containsEntry("size", "1K");
        // Kontext has no size input at all — it returns at the input's resolution.
        assertThat(buildInput("black-forest-labs/flux-kontext-pro"))
                .doesNotContainKeys("resolution", "size");
    }

    @Test
    void gptImageGetsAutoInsteadOfAnAspectItDoesNotUnderstand() {
        // "match_input_image" is what keeps the cleaned canvas aligned to the photo on
        // every other family. GPT Image doesn't have it; "auto" is its nearest thing,
        // and sending the literal string would fail the request.
        assertThat(buildInput("openai/gpt-image-1")).containsEntry("aspect_ratio", "auto");
        assertThat(buildInput("google/nano-banana-pro"))
                .containsEntry("aspect_ratio", "match_input_image");
    }

    @Test
    void gptImageCarriesTheOpenAiKeyAndIsSkippedWithoutOne() {
        ReflectionTestUtils.setField(editor, "apiToken", "tok");
        assertThat(editor.canRun("openai/gpt-image-1")).isFalse();
        assertThat(editor.canRun("google/nano-banana-pro")).isTrue();

        ReflectionTestUtils.setField(editor, "openAiApiKey", "sk-test");
        assertThat(editor.canRun("openai/gpt-image-1")).isTrue();
        assertThat(buildInput("openai/gpt-image-1")).containsEntry("openai_api_key", "sk-test");
    }

    @Test
    void nothingRunsWithoutAReplicateToken() {
        assertThat(editor.canRun("google/nano-banana-pro")).isFalse();
        assertThat(editor.hasToken()).isFalse();
    }

    @Test
    void seedreamIsPinnedToASingleImageRatherThanASeries() {
        // Seedream will happily return a SERIES from one prompt; we want the one edit.
        assertThat(buildInput("bytedance/seedream-4"))
                .containsEntry("sequential_image_generation", "disabled")
                .containsEntry("max_images", 1);
    }

    @Test
    void blankTuningValuesAreOmittedRatherThanSentEmpty() {
        Map<String, Object> input = buildInput("google/nano-banana-pro", "", "");
        assertThat(input).doesNotContainKeys("resolution", "aspect_ratio");
        assertThat(input).containsKeys("prompt", "image_input", "output_format");
    }

    @Test
    void readsTheOutputUrlOutOfEveryEnvelopeShapeReplicateUses() {
        assertThat(ReplicateImageEditor.extractOutputUrl("http://out.png")).isEqualTo("http://out.png");
        assertThat(ReplicateImageEditor.extractOutputUrl(List.of("http://out.png"))).isEqualTo("http://out.png");
        assertThat(ReplicateImageEditor.extractOutputUrl(List.of(Map.of("url", "http://out.png"))))
                .isEqualTo("http://out.png");
        assertThat(ReplicateImageEditor.extractOutputUrl(Map.of("image", "http://out.png")))
                .isEqualTo("http://out.png");
        assertThat(ReplicateImageEditor.extractOutputUrl(Map.of("output", List.of("http://out.png"))))
                .isEqualTo("http://out.png");
        assertThat(ReplicateImageEditor.extractOutputUrl(null)).isNull();
        assertThat(ReplicateImageEditor.extractOutputUrl(List.of())).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildInput(String model) {
        return buildInput(model, "1K", "match_input_image");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInput(String model, String resolution, String aspect) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                editor, "buildInput",
                new ReplicateImageEditor.Spec(model, "clean this", "http://photo",
                        aspect, resolution, "jpg"));
    }
}
