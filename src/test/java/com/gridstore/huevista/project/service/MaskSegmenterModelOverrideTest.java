package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.ai.ReplicateImageEditor;
import com.gridstore.huevista.image.model.ImageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mask half of the admin's model override.
 *
 * <p>The clean and the mask are chosen separately on purpose, so what is checked here is
 * that a mask model other than the configured one is actually reached — with the request
 * body ITS family reads, not Nano Banana's, which is the difference between a mask and a
 * 422.
 */
class MaskSegmenterModelOverrideTest {

    private static final byte[] MASK = {9, 9, 9};

    private final RestTemplate rest = mock(RestTemplate.class);
    private final ReplicateImageEditor editor = new ReplicateImageEditor(rest);
    private final ReplicateMaskSegmenter segmenter = new ReplicateMaskSegmenter(rest, editor);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void configure() {
        ReflectionTestUtils.setField(segmenter, "enabled", true);
        ReflectionTestUtils.setField(segmenter, "replicateApiToken", "tok");
        ReflectionTestUtils.setField(segmenter, "model", "google/nano-banana-pro");
        ReflectionTestUtils.setField(segmenter, "aspectRatio", "match_input_image");
        ReflectionTestUtils.setField(segmenter, "resolution", "2K");
        ReflectionTestUtils.setField(editor, "apiToken", "tok");

        // POST /models/{model}/predictions → an id; GET /predictions/{id} → done.
        when(rest.exchange(contains("/predictions"), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) Map.of("id", "pred-1")));
        when(rest.exchange(contains("/predictions/pred-1"), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) Map.of(
                        "status", "succeeded", "output", List.of("http://out/mask.png"))));
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(MASK));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedInput() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> body = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(rest).exchange(
                anyString(), eq(HttpMethod.POST), body.capture(), eq(Map.class));
        return (Map<String, Object>) body.getValue().getBody().get("input");
    }

    private String capturedEndpoint() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(rest).exchange(
                url.capture(), eq(HttpMethod.POST), any(), eq(Map.class));
        return url.getValue();
    }

    @Test
    void theConfiguredModelKeepsTheNanoBananaBodyItAlwaysHad() {
        assertThat(segmenter.generateColorCodedMask("http://photo", ImageType.INDOOR)).contains(MASK);

        Map<String, Object> input = capturedInput();
        assertThat(input).containsEntry("image_input", List.of("http://photo"))
                .containsEntry("output_format", "png")
                .containsEntry("aspect_ratio", "match_input_image")
                .containsEntry("resolution", "2K");
        assertThat(capturedEndpoint()).endsWith("/models/google/nano-banana-pro/predictions");
    }

    @Test
    void anOverriddenModelIsAskedWithITSFamilysBody() {
        // FLUX reads the photo from input_images and sizes in megapixels. Sent Nano
        // Banana's keys it answers 422, which reads in the log as "the model is broken"
        // rather than "we asked it the wrong way".
        assertThat(segmenter.generateColorCodedMask("http://photo", ImageType.OUTDOOR,
                "black-forest-labs/flux-2-max")).contains(MASK);

        Map<String, Object> input = capturedInput();
        assertThat(input).containsEntry("input_images", List.of("http://photo"))
                .containsEntry("resolution", "2 MP")
                .doesNotContainKey("image_input");
        assertThat(capturedEndpoint()).endsWith("/models/black-forest-labs/flux-2-max/predictions");
    }

    @Test
    void aPinnedVersionBelongsToTheConfiguredModelAndIsNotCarriedOntoAnother() {
        // A version hash identifies one release of one model. Sending Nano Banana Pro's
        // hash while naming Seedream asks Replicate for something that does not exist.
        ReflectionTestUtils.setField(segmenter, "modelVersion", "abc123");

        assertThat(segmenter.generateColorCodedMask("http://photo", ImageType.OUTDOOR,
                "bytedance/seedream-4")).contains(MASK);

        assertThat(capturedEndpoint()).endsWith("/models/bytedance/seedream-4/predictions");
    }

    @Test
    void theKillSwitchStillWinsOverAnOverride() {
        ReflectionTestUtils.setField(segmenter, "enabled", false);

        assertThat(segmenter.generateColorCodedMask("http://photo", ImageType.OUTDOOR,
                "google/nano-banana-2")).isEmpty();
    }
}
