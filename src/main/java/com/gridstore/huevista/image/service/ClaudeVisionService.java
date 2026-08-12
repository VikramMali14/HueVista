package com.gridstore.huevista.image.service;

import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ImageValidationException;
import com.gridstore.huevista.image.model.ImageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.tasks.UnsupportedFormatException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a photo is a room, a building, or neither.
 *
 * <p>This one word runs the rest of the pipeline. INDOOR and OUTDOOR get different
 * cleaning prompts (finish the ceiling vs. clear the sky and the wires), a different
 * accent-wall rule, a different sky filter, and a different opening palette — so
 * classifying a facade as a room does not produce a slightly-off result, it produces
 * a room's treatment applied to a house.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeVisionService {

    private final ClaudeService claude;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    /**
     * Two things this prompt is careful about, both learned from photos that came back
     * misfiled:
     *
     * <ul>
     *   <li><b>The vantage point decides, not the subject.</b> A shot taken from a
     *       balcony, through a window, or into a courtyard shows a room AND a building,
     *       and the model would pick whichever it found more interesting. The rule is
     *       where the CAMERA is, because that is what the cleaning and mask prompts are
     *       written for.</li>
     *   <li><b>INVALID is narrower than "doesn't look finished".</b> Half this product's
     *       photos are of construction sites — bare blockwork, a raw concrete soffit, no
     *       flooring — and "is this a room?" invites a no. It isn't: those are exactly
     *       the rooms people photograph to see them painted, and answering INVALID
     *       rejects the upload outright.</li>
     * </ul>
     */
    private static final String PROMPT =
            "Classify this photo for a paint colour visualisation app.\n\n" +
            "Answer INDOOR if the camera is INSIDE a room or covered space — living room, " +
            "bedroom, kitchen, bathroom, hallway, stairwell, office, shop interior — even " +
            "if a window or open doorway shows the outside, and even if the room is bare, " +
            "unfinished or still under construction (raw brick or block walls, bare " +
            "concrete ceiling, cement floor, no furniture).\n\n" +
            "Answer OUTDOOR if the camera is OUTSIDE, looking at a building or a wall from " +
            "the outside — a house facade, a shopfront, a compound wall, a porch, a " +
            "balcony seen from outside, a terrace, a building under construction.\n\n" +
            "Answer INVALID only if there is NO room and NO building in the photo at all " +
            "(a selfie or portrait, food, an animal, a landscape with no structure, a " +
            "document, a car, a screenshot).\n\n" +
            "Rules: judge by WHERE THE CAMERA IS, not by what is most interesting in the " +
            "frame. A room photographed from inside with a view out of the window is " +
            "INDOOR. A balcony or verandah photographed from the street is OUTDOOR. An " +
            "unfinished building is still a building — never INVALID. If you are unsure " +
            "between INDOOR and OUTDOOR, pick the one the camera is standing in; never " +
            "answer INVALID just because the space looks unfinished or empty.\n\n" +
            "Reply with exactly one word: INDOOR, OUTDOOR or INVALID.";

    /**
     * Sends the image to Claude Vision and returns INDOOR, OUTDOOR, or null (= INVALID).
     * Image is resized to max 1024px before sending — reduces input tokens ~10x.
     */
    public ImageType classify(MultipartFile file) throws IOException {
        return classifyResized(resizeForClassification(file));
    }

    /**
     * Same classification for a photo already in storage — used to resolve the scene of
     * an upload that never got one (every guest upload, and anything uploaded while
     * Claude was down) before the image models are prompted about it.
     *
     * @return INDOOR / OUTDOOR, or null when the photo is neither
     * @throws com.gridstore.huevista.common.exception.ExternalServiceException if the
     *         classifier can't be reached — the caller decides whether that is fatal
     */
    public ImageType classifyStored(byte[] imageBytes) throws IOException {
        return classifyResized(resizeForClassification(imageBytes));
    }

    private ImageType classifyResized(byte[] resizedBytes) {
        String base64Data = Base64.getEncoder().encodeToString(resizedBytes);

        try {
            // 16 tokens rather than 10: the answer is one word, but a model that starts
            // with a courtesy ("This is an INDOOR room") needs room to reach the word at
            // all — parseAnswer finds it in the sentence, but only if it wasn't cut off.
            String answer = claude.askUser(model, 16, List.of(
                    ClaudeService.imageBase64Block("image/jpeg", base64Data), // always JPEG after resize
                    ClaudeService.textBlock(PROMPT)
            ));
            ImageType parsed = parseAnswer(answer);
            log.debug("Claude Vision result: {} -> {}", answer, parsed);
            return parsed;
        } catch (Exception e) {
            log.error("Claude Vision API call failed: {}", e.getMessage());
            throw new ExternalServiceException("Image classification service is temporarily unavailable. Please try again.", e);
        }
    }

    /**
     * Reads the verdict out of whatever the model actually said.
     *
     * <p>This used to be an exact-match switch on the whole reply, so anything but the
     * bare word — "OUTDOOR.", "Outdoor", "This is an INDOOR room" — fell through to the
     * default and became INVALID, and INVALID rejects the upload with "please upload a
     * photo of a room or a house". A punctuation mark could therefore turn a perfectly
     * good photo of a house into a refusal to accept it, which is the worst possible
     * failure for a step whose only job is to route the photo.
     *
     * <p>So: find the verdict as a WORD anywhere in the reply. When the reply somehow
     * names more than one, the FIRST one wins — models lead with their answer and
     * qualify afterwards ("OUTDOOR, though the balcony could read as indoor"). A reply
     * naming none is null, same as INVALID: that is a model not answering the question,
     * and guessing on its behalf is worse than saying so.
     */
    static ImageType parseAnswer(String answer) {
        if (answer == null) return null;
        String upper = answer.toUpperCase(Locale.ROOT);
        int indoor = indexOfWord(upper, "INDOOR");
        int outdoor = indexOfWord(upper, "OUTDOOR");
        if (indoor < 0 && outdoor < 0) return null;             // INVALID or no answer
        if (indoor < 0) return ImageType.OUTDOOR;
        if (outdoor < 0) return ImageType.INDOOR;
        return indoor < outdoor ? ImageType.INDOOR : ImageType.OUTDOOR;
    }

    /**
     * Where {@code word} appears as a whole word, or -1. Whole-word matching is what
     * keeps "OUTDOOR" from being read as an "INDOOR" at offset 3 — a substring search
     * would call every outdoor photo indoor.
     */
    private static int indexOfWord(String haystack, String word) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + word + "\\b")
                .matcher(haystack);
        return m.find() ? m.start() : -1;
    }

    // Resize to max 1024x1024 JPEG at 85% quality before sending to Claude.
    // Cuts input tokens ~10x and keeps classification accuracy identical.
    private byte[] resizeForClassification(MultipartFile file) throws IOException {
        return resize(() -> Thumbnails.of(file.getInputStream()));
    }

    private byte[] resizeForClassification(byte[] bytes) throws IOException {
        return resize(() -> Thumbnails.of(new java.io.ByteArrayInputStream(bytes)));
    }

    /** Shared resize/encode for both sources, including the "we can't decode this" path. */
    private byte[] resize(ThumbnailSource source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            source.builder()
                    .size(1024, 1024)
                    .keepAspectRatio(true)
                    .outputFormat("jpeg")
                    .outputQuality(0.85)
                    .toOutputStream(out);
        } catch (UnsupportedFormatException e) {
            // Content-Type header said JPEG/PNG/WebP, but the bytes are not a format
            // ImageIO can decode (e.g. HEIC from iOS, AVIF, or a corrupted file).
            throw new ImageValidationException(
                    "Unable to read the image. The file may be corrupted or in an unsupported format " +
                    "(e.g. HEIC from iPhone). Please upload a JPEG, PNG, or WebP image."
            );
        }
        return out.toByteArray();
    }

    @FunctionalInterface
    private interface ThumbnailSource {
        net.coobird.thumbnailator.Thumbnails.Builder<?> builder() throws IOException;
    }
}
