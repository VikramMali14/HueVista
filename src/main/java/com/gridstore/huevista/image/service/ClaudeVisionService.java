package com.gridstore.huevista.image.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ImageValidationException;
import com.gridstore.huevista.image.model.HouseType;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.SceneAnalysis;
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

    /**
     * Static, and not the Spring bean, so {@link #parseAnalysis} stays a pure static
     * function testable without a context — exactly like {@link #parseAnswer}, which is
     * where every regression in this file has been caught. Reading a small JSON object
     * needs no configuration, and a shared ObjectMapper is thread-safe for reads.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    /**
     * The INDOOR / OUTDOOR / INVALID definitions themselves, with no lead-in and no
     * closing instruction — shared verbatim by {@link #PROMPT} and
     * {@link #ANALYSIS_PROMPT}.
     *
     * <p>Shared rather than copied because these four paragraphs are the load-bearing
     * part of both prompts, and two copies would drift the first time only one of them
     * was tuned. The scene answer routes the entire pipeline, so the richer prompt has
     * to ask for it in exactly the words the one-word prompt was calibrated on.
     *
     * <p>Two things they are careful about, both learned from photos that came back
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
    private static final String SCENE_RULES =
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
            "answer INVALID just because the space looks unfinished or empty.\n\n";

    /**
     * The upload path's prompt: the scene rules, and nothing else. One word back.
     *
     * <p>Unchanged by the analysis work below, and that is the point — every customer
     * upload runs this, so it keeps its 16-token ceiling and its one-word answer, and
     * costs exactly what it always has.
     */
    private static final String PROMPT =
            "Classify this photo for a paint colour visualisation app.\n\n" +
            SCENE_RULES +
            "Reply with exactly one word: INDOOR, OUTDOOR or INVALID.";

    /**
     * The richer prompt: the same scene question, plus what kind of place this is and
     * what colour its walls are right now. Answered as JSON so three answers cost one
     * call — the image is ~1,100 input tokens and asking again is what would actually
     * cost money, not the extra sentences.
     *
     * <p>This prompt is NOT on the upload path. {@link #classify} still sends
     * {@link #PROMPT} and still allows 16 output tokens, so a customer's upload behaves
     * and costs exactly as it did before this existed. Only a run that explicitly asks
     * for the analysis pays for it.
     *
     * <p>Two things it is careful about, for the same reason the scene rules are:
     *
     * <ul>
     *   <li><b>UNKNOWN is offered as a real answer, repeatedly.</b> A model given a list
     *       of eleven types and no way out will pick one, and a confidently wrong
     *       "BATHROOM" puts a tile clause into the prompt for a bedroom. The type only
     *       ever adds a sentence or two, so a refusal to answer costs nothing while a
     *       guess costs accuracy.</li>
     *   <li><b>The colour is allowed to be null, and the reason is stated.</b> Half these
     *       photos are of walls that have never been painted — bare plaster, raw block,
     *       a chalky putty coat — and "what colour is this wall" invites a hex for all of
     *       them. That hex ends up on screen beside a catalogue shade the customer may
     *       buy tins of, which is why the prompt says out loud what the wrong answer
     *       costs.</li>
     * </ul>
     */
    private static final String ANALYSIS_PROMPT =
            "Analyse this photo for a paint colour visualisation app. Answer three " +
            "questions about it.\n\n" +
            "1. SCENE — where is the camera?\n\n" +
            SCENE_RULES +
            "2. TYPE — what kind of place is this? Pick exactly ONE of the names below, " +
            "or UNKNOWN.\n" +
            "  Outside: INDEPENDENT_HOUSE (a standalone house, bungalow, villa or " +
            "farmhouse), APARTMENT_BLOCK (a multi-storey residential block with repeated " +
            "floors or balconies), ROW_HOUSE (one unit in a terrace, sharing walls with " +
            "its neighbours), SHOPFRONT (a shop, showroom or commercial frontage at " +
            "street level), COMPOUND_WALL (a boundary wall, compound wall or gate — no " +
            "roof, no windows, no interior).\n" +
            "  Inside: LIVING_ROOM, BEDROOM, KITCHEN, BATHROOM, STAIRWELL_OR_HALLWAY (a " +
            "stairwell, landing or corridor), OFFICE_OR_SHOP (an office, shop or " +
            "showroom interior rather than a home).\n" +
            "The type MUST agree with the scene: never give an inside type for an OUTDOOR " +
            "photo, or an outside type for an INDOOR one. UNKNOWN is a perfectly good " +
            "answer and is preferred to a guess — answer UNKNOWN whenever two of these " +
            "fit equally well, or none of them fits.\n\n" +
            "3. WALL COLOUR — what colour is the largest PAINTED wall right now?\n" +
            "Report the colour as it ACTUALLY APPEARS in this photograph, under this " +
            "photograph's own light: a '#RRGGBB' hex, plus a short everyday name for it " +
            "('faded terracotta', 'pale cream', 'dusty blue').\n" +
            "Answer null for BOTH — do not guess — whenever the largest wall is not " +
            "painted at all: bare cement plaster, a bare putty coat, raw brick or " +
            "blockwork, exposed stone, tile, marble or cladding. Answer null too when " +
            "the walls are in deep shadow, blown out by the light, or too small in the " +
            "frame to read a colour from. This colour is shown to a customer next to a " +
            "catalogue shade they may be about to buy, so no answer is far better than a " +
            "plausible one.\n" +
            "If the trim — frames, skirting, bands, parapet edges — is clearly a " +
            "DIFFERENT colour from the walls, report it as trimHex; otherwise null.\n\n" +
            "Reply with ONLY this JSON object, and nothing before or after it:\n" +
            "{\"scene\": \"INDOOR|OUTDOOR|INVALID\", \"type\": \"ONE_OF_THE_NAMES_ABOVE\", " +
            "\"wallHex\": \"#RRGGBB\" or null, \"wallColour\": \"short name\" or null, " +
            "\"trimHex\": \"#RRGGBB\" or null}";

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

    /**
     * The richer look at a photo already in storage: scene, house type and current wall
     * colour, from one call.
     *
     * <p>Deliberately NOT wired into {@link #classify}. The upload path runs on every
     * photo every customer sends, and it has one job — decide whether to accept the
     * upload and which way to route it. Nothing it does needs a house type, so paying
     * for one on every upload would buy data at the moment we have the least use for it.
     * This is called later instead, from the run that actually wants the answer.
     *
     * <p>Fails soft in one direction only. A model that cannot be reached still throws,
     * because the scene is load-bearing and the caller must decide whether that is
     * fatal — same contract as {@link #classifyStored}. A model that answers
     * unhelpfully does not throw: an unreadable house type or a malformed hex simply
     * comes back absent, and the run continues on the scene alone.
     *
     * @return the analysis, whose {@code scene} is null when the photo is neither a room
     *         nor a building
     * @throws com.gridstore.huevista.common.exception.ExternalServiceException if the
     *         classifier can't be reached
     */
    public SceneAnalysis analyseStored(byte[] imageBytes) throws IOException {
        byte[] resized = resizeForClassification(imageBytes);
        String base64Data = Base64.getEncoder().encodeToString(resized);
        try {
            // 400 tokens: the JSON is ~80, but a model that opens with a sentence of
            // preamble before the object needs room to reach the closing brace. Truncated
            // JSON parses as nothing and costs the whole analysis, and the fallback below
            // can only recover the scene from it.
            String answer = claude.askUser(model, 400, List.of(
                    ClaudeService.imageBase64Block("image/jpeg", base64Data), // always JPEG after resize
                    ClaudeService.textBlock(ANALYSIS_PROMPT)
            ));
            SceneAnalysis parsed = parseAnalysis(answer);
            log.info("Claude Vision analysis: scene={} type={} wall={} ({}) trim={}",
                    parsed.scene(), parsed.houseType(), parsed.wallHex(),
                    parsed.wallColourName(), parsed.trimHex());
            return parsed;
        } catch (Exception e) {
            log.error("Claude Vision analysis call failed: {}", e.getMessage());
            throw new ExternalServiceException(
                    "Image analysis service is temporarily unavailable. Please try again.", e);
        }
    }

    /**
     * Reads the analysis out of whatever the model actually said.
     *
     * <p>Degrades one field at a time, on purpose. The scene routes the whole pipeline
     * and the other three only add sentences to a prompt, so nothing about a bad house
     * type or a mangled hex may be allowed to cost us the scene:
     *
     * <ul>
     *   <li>Reply isn't JSON at all → fall back to {@link #parseAnswer} over the raw
     *       text, which is the exact reading the one-word path has always used. A model
     *       that ignored the format but answered the question still routes the photo.</li>
     *   <li>Type is unrecognised, or contradicts its own scene answer (OUTDOOR +
     *       BEDROOM) → {@link HouseType#UNKNOWN}. The scene wins that argument because
     *       it is the answer four downstream decisions already depend on.</li>
     *   <li>Hex isn't six hex digits → dropped, along with the colour name that
     *       described it. A name with no swatch beside it is worse than neither.</li>
     * </ul>
     */
    static SceneAnalysis parseAnalysis(String answer) {
        if (answer == null) return SceneAnalysis.sceneOnly(null);
        JsonNode root;
        try {
            root = MAPPER.readTree(ClaudeService.stripCodeFences(answer));
        } catch (Exception e) {
            log.warn("Claude Vision analysis was not JSON, reading the scene out of the text: {}",
                    e.getMessage());
            return SceneAnalysis.sceneOnly(parseAnswer(answer));
        }
        if (root == null || !root.isObject()) {
            return SceneAnalysis.sceneOnly(parseAnswer(answer));
        }

        // parseAnswer rather than a string compare: it already handles the model dressing
        // its answer up, and it is the reading the scene has always been decided by.
        ImageType scene = parseAnswer(text(root, "scene"));
        HouseType type = HouseType.parse(text(root, "type"));
        if (!type.fits(scene)) {
            log.warn("Claude Vision answered scene={} but type={} — dropping the type",
                    scene, type);
            type = HouseType.UNKNOWN;
        }

        String wallHex = normaliseHex(text(root, "wallHex"));
        String wallName = wallHex == null ? null : trimToNull(text(root, "wallColour"));
        return new SceneAnalysis(scene, type, wallHex, wallName,
                normaliseHex(text(root, "trimHex")));
    }

    /** A JSON string field, or null — including for an explicit JSON {@code null}. */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * "#RRGGBB" lowercased, or null for anything that isn't six hex digits.
     *
     * <p>Strict rather than forgiving. This value is rendered as a swatch next to
     * catalogue shades, and every downstream consumer — the frontend's colour maths
     * included — assumes a six-digit hex. A model that answers "beige", "rgb(200,180,150)"
     * or the JSON string "null" gets no colour rather than a colour nobody can render.
     */
    static String normaliseHex(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return null;
        }
        return "#" + s.toLowerCase(Locale.ROOT);
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
