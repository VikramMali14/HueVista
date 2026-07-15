package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.image.model.ImageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates the colour-coded wall mask by asking an image-EDITING model on
 * Replicate to flood each paintable surface with a flat category colour.
 * Model-agnostic: the configured model decides which request schema is sent.
 *
 * Supported model families (same REPLICATE_API_TOKEN for both):
 *   black-forest-labs/flux-2-max — FLUX.2 [max] (DEFAULT — highest editing
 *                                  consistency in the FLUX.2 lineup; inputs:
 *                                  input_images + aspect_ratio, both support
 *                                  "match_input_image" so the mask keeps the
 *                                  photo's exact aspect)
 *   black-forest-labs/flux-2-*   — other FLUX.2 tiers (pro/flex), same schema
 *   google/nano-banana-pro / -2  — Gemini Image family (previous default;
 *                                  inputs: image_input + aspect_ratio +
 *                                  1K/2K/4K resolution)
 *
 * Honest caveat: this is generative image EDITING, not pixel extraction.
 * Pixel alignment isn't guaranteed. The downstream post-processing
 * (colour gate, morph clean, edge snap — see SegmentationService) exists
 * precisely to absorb the model's small misregistrations.
 *
 * Configuration (key names kept from the Nano Banana era so existing
 * REPLICATE_NANO_BANANA_* deployment env vars keep working):
 *   replicate.nano-banana.enabled       — kill switch (default false)
 *   replicate.nano-banana.model         — owner/name (default black-forest-labs/flux-2-max)
 *   replicate.nano-banana.model-version — pin a version hash for production
 *
 * Cost: ~$0.03-0.12 per mask depending on the model. One colour-coded
 * call per upload. User explicitly asked for quality over cost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicateMaskSegmenter {

    private final RestTemplate restTemplate;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.nano-banana.model:black-forest-labs/flux-2-max}")
    private String model;

    @Value("${replicate.nano-banana.model-version:}")
    private String modelVersion;

    @Value("${replicate.nano-banana.enabled:false}")
    private boolean enabled;

    /**
     * Output aspect ratio requested from the model. Image models generate
     * into fixed aspect buckets by default, so WITHOUT this the colour-coded
     * mask can come back at a different aspect than the photo — and every
     * region mask is then systematically stretched or shifted off the real
     * surfaces. "match_input_image" pins the output to the photo's own
     * aspect and is supported by BOTH the FLUX.2 and Nano Banana families.
     * Blank = omit the parameter.
     */
    @Value("${replicate.nano-banana.aspect-ratio:match_input_image}")
    private String aspectRatio;

    /** Output resolution when the model supports it; higher means finer mask
     *  edges. Configured in Nano Banana units (1K/2K/4K) — for FLUX models
     *  the value is translated to megapixels ("1 MP"/"2 MP"/"4 MP", see
     *  {@link #resolutionForModel}); FLUX also accepts "match_input_image"
     *  directly. Blank (default) = omit and take the model's native output
     *  size. */
    @Value("${replicate.nano-banana.resolution:}")
    private String resolution;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 90; // image gen can take 60-90s

    public boolean isConfigured() {
        return enabled
                && replicateApiToken != null && !replicateApiToken.isBlank()
                && model != null && !model.isBlank();
    }

    /**
     * Generates a binary mask image for one paintable surface category.
     * The model receives the original photo (via URL) and a prompt
     * describing exactly which surface to mask.
     *
     * @param imageUrl           publicly-fetchable URL of the source photo
     * @param surfaceDescription what to mask (e.g. "the main painted wall")
     * @return mask bytes (PNG/JPEG, white = surface) or Optional.empty()
     */
    public Optional<byte[]> generateMask(String imageUrl, String surfaceDescription) {
        if (!isConfigured()) {
            log.debug("Mask segmenter not configured — skipping");
            return Optional.empty();
        }
        try {
            log.info("Mask segmenter [{}]: requesting mask for '{}'", model, surfaceDescription);

            String prompt = buildMaskPrompt(surfaceDescription);

            // buildImageEditInput picks the request schema for the configured
            // model family (FLUX: input_images / Nano Banana: image_input);
            // output_format "png" preferred for masks either way.
            Map<String, Object> input = buildImageEditInput(prompt, imageUrl);

            String predictionId = startPrediction(input);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("Mask segmenter prediction timed out or failed");
                return Optional.empty();
            }

            String maskUrl = extractOutputUrl(result.get("output"));
            if (maskUrl == null) {
                log.warn("Mask segmenter returned no output URL");
                return Optional.empty();
            }

            byte[] bytes = downloadBytes(maskUrl);
            log.info("Mask segmenter generated mask for '{}': {} bytes", surfaceDescription, bytes.length);
            return Optional.of(bytes);

        } catch (Exception e) {
            log.warn("Mask segmenter failed for '{}': {}", surfaceDescription, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Produces a SINGLE flat colour-blocked image covering all three paint
     * categories at once. Red = main wall, Green = accent wall, Blue = trim,
     * Black = everything else (including the door panels and metal railings —
     * those are kept as fixed dark-brown features, never recoloured, so they
     * fall in Black). The caller splits the image into per-category binary
     * masks server-side via {@link MaskProcessor#splitColorCodedMask}.
     *
     * <p>This is framed as an <b>edit of the actual (cleaned) photo</b>, not as
     * an abstract segmentation map: the model floods each real surface with a
     * flat category colour in place. Editing the photo tracks its true edges far
     * more faithfully than generating a mask from scratch (image-editing
     * models don't guarantee pixel alignment when generating), and the flat-fill
     * instruction — deliberately ignoring the photo's own shadows — keeps the
     * downstream colour-threshold split clean instead of punching holes wherever
     * a wall was shaded.
     *
     * <p>Big advantage over three separate single-category calls: 1 Replicate
     * call instead of 3 (3× faster, 3× cheaper), the model sees all categories
     * at once so a pixel can only belong to ONE category — no inter-mask
     * overlap.
     *
     * @param scene drives HOW the accent wall is chosen — interiors always
     *              highlight exactly one prominent wall (typically behind the
     *              bed/sofa); exteriors feature at most ONE architecturally
     *              distinct wall/volume and may legitimately produce zero when
     *              the facade is a single plain mass (an accent carved out of
     *              a flat wall reads as a painting mistake, so we don't force
     *              one). {@link SegmentationService} tolerates a missing
     *              accent region — only "main" is required.
     */
    public Optional<byte[]> generateColorCodedMask(String imageUrl, ImageType scene) {
        if (!isConfigured()) {
            log.debug("Mask segmenter not configured — skipping");
            return Optional.empty();
        }
        try {
            boolean forceAccent = scene == ImageType.INDOOR;
            log.info("Mask segmenter [{}]: requesting COLOR-CODED mask (scene={}, forceAccent={})",
                    model, scene, forceAccent);

            Map<String, Object> input = buildImageEditInput(colorCodedPrompt(forceAccent), imageUrl);

            String predictionId = startPrediction(input);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("Mask segmenter color-coded prediction timed out");
                return Optional.empty();
            }
            String maskUrl = extractOutputUrl(result.get("output"));
            if (maskUrl == null) {
                log.warn("Mask segmenter color-coded prediction had no output URL");
                return Optional.empty();
            }
            byte[] bytes = downloadBytes(maskUrl);
            log.info("Mask segmenter color-coded mask: {} bytes", bytes.length);
            return Optional.of(bytes);
        } catch (Exception e) {
            log.warn("Mask segmenter color-coded mask failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Single comprehensive prompt for the colour-blocked approach. Asks the
     * model to EDIT the photo, flooding each surface with one of four flat
     * colours (three paint categories plus a black "nothing" background).
     * Structured as ROLE → OUTPUT CONTRACT → CLASSIFY → FLOOD FILL →
     * SELF-CHECK so the hard invariants (four colours only, nothing removed)
     * are read before the per-category detail. Tested wording — be careful
     * editing.
     *
     * <p>Two invariants matter most:
     * <ul>
     *   <li><b>Nothing may be removed.</b> The model is editing the photo, so
     *   without an explicit rule it happily "cleans up" railings and grilles
     *   by flooding wall colour straight over them — the railing then lands
     *   inside the red main-wall mask and the downstream repaint paints over
     *   it, deleting it from the render. Railings must survive as BLACK
     *   silhouettes in place.</li>
     *   <li><b>Accent by architectural merit.</b> The GREEN paragraph is the
     *   only part that varies by scene: {@link #ACCENT_ALWAYS} (interiors)
     *   always highlights exactly one room wall (a full wall bounded by real
     *   corners always exists indoors); {@link #ACCENT_CONDITIONAL}
     *   (exteriors) features at most one distinct wall/volume and allows ZERO
     *   when the facade is a single plain mass — an accent patch carved out
     *   of a flat wall stops mid-wall and reads as a painting mistake.</li>
     * </ul>
     */
    static String colorCodedPrompt(boolean forceAccent) {
        return COLOR_CODED_HEAD
             + (forceAccent ? ACCENT_ALWAYS : ACCENT_CONDITIONAL)
             + COLOR_CODED_TAIL;
    }

    private static final String COLOR_CODED_HEAD =
            "ROLE\n\n"
          + "You are performing IMAGE EDITING on this exact photograph of a room or "
          + "building — you are NOT generating a new scene. Keep the camera angle, "
          + "geometry, resolution and every edge exactly as in the input photo. Act "
          + "as an architect-trained colour consultant preparing a paint mask: "
          + "RED = main body walls, GREEN = accent/highlighter wall, BLUE = "
          + "borders/trim, BLACK = everything else.\n\n"
          + "OUTPUT CONTRACT (highest priority — read first)\n\n"
          + "- Output ONE image only: same width, same height, same aspect ratio as "
          + "the input, with every surface boundary pixel-aligned to the photo.\n"
          + "- Every pixel must be EXACTLY one of these four values — no other "
          + "colour may appear anywhere:\n"
          + "  RED #FF0000 (255, 0, 0)\n"
          + "  GREEN #00FF00 (0, 255, 0)\n"
          + "  BLUE #0000FF (0, 0, 255)\n"
          + "  BLACK #000000 (0, 0, 0)\n"
          + "- WHITE IS FORBIDDEN. Grey is forbidden. A wall that is white in the "
          + "photo is still recoloured into its category (accent wall → green, "
          + "other walls → red, trim → blue).\n"
          + "- NOTHING MAY BE REMOVED, ADDED OR MOVED. Every object in the photo "
          + "keeps its exact place, shape and size — especially RAILINGS, grilles, "
          + "gates, doors, pipes, wires and AC units. You are recolouring surfaces, "
          + "NEVER erasing objects: anything standing in front of a wall is traced "
          + "around in black, not painted over, not deleted, not simplified away.\n"
          + "- No gradients, no shading, no texture, no lighting, no transparency, "
          + "no blending. Hard colour boundaries only — no anti-aliasing or "
          + "feathered edges.\n"
          + "- No text, labels, legends, watermarks or annotations. Output only the "
          + "flat colour-blocked image.\n\n"
          + "STEP 1 — CLASSIFY EVERY SURFACE BY ITS ROLE\n\n"
          + "Decide what each surface IS, never how it currently looks. Current "
          + "paint colour, sunlight and shadow are irrelevant: a wall half in "
          + "shadow is still ONE wall and gets ONE colour; trim painted the same "
          + "colour as the wall is still trim.\n\n"
          + "1. MAIN WALLS → RED (#FF0000)\n\n"
          + "All flat painted plaster/concrete/drywall wall planes — the surfaces a "
          + "painter would roll in the main body colour (the largest painted area). "
          + "This includes painted columns and porch pillars, solid masonry balcony "
          + "parapet fronts, and painted compound/boundary walls and gate pillars. "
          + "Every painted wall is RED unless it qualifies as the single accent "
          + "wall described next.\n\n";

    /** Exterior/unknown: ZERO OR ONE accent, decided by architectural merit.
     *  An accent colour is only correct when it starts and stops on real
     *  architectural edges; a green patch carved out of a flat single-mass
     *  facade dies mid-wall and reads as a painting mistake, so a plain
     *  facade legitimately yields no green at all. When unsure, a plane is
     *  NOT the accent (red) — never two or more greens. */
    private static final String ACCENT_CONDITIONAL =
            "2. ACCENT / HIGHLIGHTER WALL → GREEN (#00FF00) — ZERO OR ONE, "
          + "DECIDED BY ARCHITECTURAL MERIT\n\n"
          + "Architect's rule: an accent colour is only correct when it can start "
          + "and stop on real architectural edges — outside corners, inside "
          + "corners, a projection, a recess, a change of plane. A colour break "
          + "that dies in the middle of a flat wall reads as a painting mistake. "
          + "Therefore:\n\n"
          + "PAINT ONE WALL GREEN only if the building offers a genuine candidate, "
          + "in this priority order:\n"
          + "(a) a wall ALREADY clearly painted a different colour from the main "
          + "body — an existing feature wall, accent strip, or differently painted "
          + "perpendicular wall → that is the accent; keep it; else\n"
          + "(b) a DISTINCT ARCHITECTURAL VOLUME a designer would naturally "
          + "feature even though it is currently the same colour as the rest: a "
          + "projecting or corner stair/lift tower, a tall vertical block rising "
          + "past the roofline, a porch or entrance mass, a clearly projected or "
          + "recessed entrance bay, or a prominent perpendicular wing. Paint that "
          + "ENTIRE volume — all of its visible faces, top to bottom — green. "
          + "Windows, chajjas, railings and doors sitting on the green volume "
          + "still follow their own categories (blue/black); green covers only "
          + "its wall skin.\n\n"
          + "If several candidates exist, feature exactly ONE, preferring: "
          + "existing feature wall > the volume holding or framing the main "
          + "entrance > tallest vertical volume > largest projecting wing.\n\n"
          + "PAINT ZERO GREEN — all walls red — when the facade is one continuous "
          + "plain mass with no differently painted wall and no projection, "
          + "recess, tower, bay or perpendicular wing. Do NOT invent an accent by "
          + "carving an arbitrary patch out of a flat wall.\n\n"
          + "Tie-breaker: if unsure whether a plane is distinct enough to feature "
          + "→ it is NOT the accent; paint it RED. Never output two or more "
          + "greens.\n\n";

    /** Interior: exactly one accent wall — a full wall bounded by real corners
     *  and the ceiling always exists indoors, so the merit test always passes
     *  and the product's three-region experience (main/accent/trim) holds. */
    private static final String ACCENT_ALWAYS =
            "2. ACCENT / HIGHLIGHTER WALL → GREEN (#00FF00) — EXACTLY ONE\n\n"
          + "A room always offers a genuine accent candidate: a full wall bounded "
          + "by real corners, floor and ceiling. Pick the single best wall to "
          + "highlight: if one wall is ALREADY painted a different colour from "
          + "the rest, that is the accent — keep it. Otherwise choose the room's "
          + "natural feature wall — typically the largest, least obstructed wall "
          + "behind the bed or sofa, or the main wall facing the camera — and "
          + "paint that ENTIRE wall green, corner to corner, floor to ceiling. "
          + "Furniture, frames, curtains and fixtures in front of it keep their "
          + "own category (black); green covers only the wall skin behind them. "
          + "Paint exactly ONE wall green — never two — and every remaining "
          + "painted wall red.\n\n";

    private static final String COLOR_CODED_TAIL =
            "3. TRIM / BORDERS → BLUE (#0000FF) — find ALL of these, miss none\n\n"
          + "- Chajjas: the flat sunshade/weather-shade slabs projecting "
          + "horizontally above windows and doors (their top, front edge AND "
          + "visible underside);\n"
          + "- the parapet coping/cap running along the very top roofline, and "
          + "the top band of the parapet wall;\n"
          + "- horizontal string-course bands, lintel bands, plinth bands, and "
          + "any decorative banding running across the facade;\n"
          + "- window sills, ledges, and the raised plaster surrounds/borders "
          + "framing windows and doors;\n"
          + "- window frames, door FRAMES (the surround only — not the door "
          + "leaf), skirting/baseboards, cornices/crown moulding, fascia under "
          + "the roof, and caps on compound-wall pillars.\n\n"
          + "These projecting slabs, copings and bands are SEPARATE trim pieces "
          + "even though they are attached to the wall and may be the same colour "
          + "in the photo. A real painter picks them out in a contrast colour, so "
          + "they are BLUE — never swallowed into the red wall. Tie-breaker: if "
          + "you are unsure whether a narrow band, ledge or projecting slab is "
          + "wall or trim, it is TRIM (blue). But metalwork is NEVER trim: a "
          + "railing or grille mounted on a parapet, balcony or staircase is NOT "
          + "blue — only the masonry band or coping beneath it is; the railing "
          + "itself is BLACK, below.\n\n"
          + "4. EVERYTHING ELSE → BLACK (#000000)\n\n"
          + "Sky, clouds, ground, soil, road, sidewalk, vegetation, trees, "
          + "vehicles, people, furniture, floors, ceilings; the door LEAVES/"
          + "panels themselves and gates; all metalwork; glass panes inside "
          + "windows; stone cladding, exposed brick, ceramic tile, marble, wood; "
          + "AC units, drainpipes, wires, light fixtures, electrical boxes, "
          + "meters, water tanks, signage, mailboxes, decor. Doors and railings "
          + "are kept as fixed features in the real scheme (dark-brown doors, "
          + "charcoal-grey metalwork), so here they belong in BLACK — never in "
          + "red, green or blue.\n\n"
          + "RAILINGS ARE PROTECTED — this rule overrides everything else: every "
          + "balcony railing, terrace/parapet railing, staircase railing, "
          + "handrail, balustrade, window grille and safety grille in the photo "
          + "MUST still be present in the output, in its exact position and "
          + "shape, as a BLACK silhouette. Never delete a railing, never thin "
          + "it, never merge it into the wall behind it, and never colour it "
          + "red, green or blue. Where wall, sky or trim is visible between or "
          + "behind the bars, that background keeps its own colour and the bars "
          + "stay black on top of it. If the bars are too fine to trace "
          + "individually, keep the whole railing band black rather than losing "
          + "it.\n\n"
          + "STEP 2 — FLOOD FILL\n\n"
          + "Fill each group with its ONE flat, solid, fully saturated colour: a "
          + "single identical colour value across every pixel of that surface. "
          + "Completely ignore the photo's own shadows, highlights and colour "
          + "variation while filling. Flood only the surface itself: where a "
          + "railing, grille, pipe, cable or any other object crosses in front "
          + "of a surface, the object stays BLACK on top and the surface colour "
          + "appears only around and between it — flooding must never wipe an "
          + "object out.\n\n"
          + "FINAL SELF-CHECK — verify before returning the image\n\n"
          + "- Compare against the photo: every railing, grille and gate is "
          + "still there, black, in its exact place — none missing, none "
          + "absorbed into a red, green or blue area.\n"
          + "- GREEN follows the accent rule above — never two or more greens; "
          + "when a green is present, the whole plane/volume is filled across "
          + "every visible face and its edges land on true corners, recesses or "
          + "plane changes — never mid-wall.\n"
          + "- Every chajja, parapet coping, band, sill, frame and border is "
          + "traced in BLUE — none left red.\n"
          + "- Door panels, railings, glass, sky, ground and vegetation are all "
          + "BLACK.\n"
          + "- Zero white, grey or off-palette pixels. Zero gradients, zero soft "
          + "edges.\n"
          + "- Output has the same dimensions as the input, with every edge "
          + "aligned pixel-for-pixel.\n\n"
          + "Return only the finished flat colour-block image.\n";

    private String buildMaskPrompt(String surfaceDescription) {
        return ("Generate a black-and-white binary segmentation MASK image. The mask must be the "
                + "same exact dimensions as the input photograph.\n\n"
                + "TARGET SURFACE: " + surfaceDescription + "\n\n"
                + "RULES:\n"
                + "- Pure WHITE (#FFFFFF) pixels for the target surface only.\n"
                + "- Pure BLACK (#000000) pixels for everything else: sky, ground, vegetation, "
                + "vehicles, doors, windows, stone, brick, tile, fixtures, decorations.\n"
                + "- Mask must be PIXEL-ALIGNED with the input photo — same resolution, same "
                + "object positions.\n"
                + "- No grey pixels, no anti-aliasing, no gradients, no text, no annotations.\n"
                + "- Output: the mask image only.\n");
    }

    /**
     * Base input for an image-edit prediction, plus the OPTIONAL aspect-ratio
     * and resolution controls when configured. The source-image key differs
     * per family: FLUX.2 takes {@code input_images}, Nano Banana takes
     * {@code image_input}. Both families accept {@code aspect_ratio} with
     * "match_input_image", which is what keeps the mask pinned to the photo's
     * exact aspect. If a model variant rejects an optional key,
     * {@link #startPrediction} retries once without the optional keys.
     */
    private Map<String, Object> buildImageEditInput(String prompt, String imageUrl) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("prompt", prompt);
        input.put(isFluxModel() ? "input_images" : "image_input", List.of(imageUrl));
        input.put("output_format", "png");
        if (aspectRatio != null && !aspectRatio.isBlank()) {
            input.put("aspect_ratio", aspectRatio.trim());
        }
        String res = resolutionForModel();
        if (res != null) {
            input.put("resolution", res);
        }
        return input;
    }

    /** FLUX family speaks a different input schema (input_images, megapixel
     *  resolutions) than the Gemini/Nano Banana family. Matches any
     *  black-forest-labs model so flux-2-pro/flex work too. */
    private boolean isFluxModel() {
        return model != null && model.startsWith("black-forest-labs/");
    }

    /**
     * Resolution value in the configured model's own units, or null to omit
     * the key. The config is written in Nano Banana units (1K/2K/4K); FLUX
     * models size in megapixels instead, so those values are translated
     * (1K→"1 MP", 2K→"2 MP", 4K→"4 MP" — FLUX caps at 4 MP / 2048px longest
     * side, which matches MAX_MASK_DIM downstream). Anything else — e.g.
     * FLUX's own "match_input_image" or an explicit "2 MP" — passes through
     * untouched, and {@link #startPrediction}'s retry-without-optionals
     * covers a value the model rejects.
     */
    private String resolutionForModel() {
        if (resolution == null || resolution.isBlank()) return null;
        String value = resolution.trim();
        if (isFluxModel()) {
            return switch (value.toUpperCase()) {
                case "1K" -> "1 MP";
                case "2K" -> "2 MP";
                case "4K" -> "4 MP";
                default -> value;
            };
        }
        return value;
    }

    private String startPrediction(Map<String, Object> input) {
        try {
            return doStartPrediction(input);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 400/422 usually means an input the model version doesn't know.
            // Drop the optional tuning keys and retry once — a mask at the
            // model's default aspect/resolution beats no mask at all.
            boolean hadOptional = input.containsKey("aspect_ratio") || input.containsKey("resolution");
            if (hadOptional && (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 422)) {
                log.warn("Mask model rejected optional inputs ({}), retrying without " +
                        "aspect_ratio/resolution: {}", e.getStatusCode(), e.getResponseBodyAsString());
                Map<String, Object> slim = new java.util.HashMap<>(input);
                slim.remove("aspect_ratio");
                slim.remove("resolution");
                try {
                    return doStartPrediction(slim);
                } catch (Exception retryError) {
                    log.warn("Mask model retry without optional inputs also failed: {}",
                            retryError.getMessage());
                    return null;
                }
            }
            log.warn("Failed to start mask prediction: {} {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("Failed to start mask prediction: {}", e.getMessage());
            return null;
        }
    }

    private String doStartPrediction(Map<String, Object> input) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        boolean hasPinnedVersion = modelVersion != null && !modelVersion.isBlank();
        Map<String, Object> body = hasPinnedVersion
                ? Map.of("version", modelVersion, "input", input)
                : Map.of("input", input);
        String endpoint = hasPinnedVersion
                ? REPLICATE_BASE + "/predictions"
                : REPLICATE_BASE + "/models/" + model + "/predictions";

        ResponseEntity<Map> response = restTemplate.exchange(
                endpoint, HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        String id = (String) response.getBody().get("id");
        log.debug("Mask prediction started: id={}", id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilDone(String predictionId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + replicateApiToken);
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            String status = (String) body.get("status");
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("Mask prediction terminal status: {} error: {}",
                        status, body.get("error"));
                return null;
            }
        }
        return null;
    }

    /**
     * Model output on Replicate can be a single URL string (FLUX.2), a list
     * of URLs (Nano Banana), or a dict — depends on the model. Handle all
     * three.
     */
    @SuppressWarnings("unchecked")
    private String extractOutputUrl(Object output) {
        if (output == null) return null;
        if (output instanceof String s && !s.isBlank()) return s;
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String s) return s;
            if (first instanceof Map<?, ?> m) {
                Object u = m.get("url");
                if (u instanceof String s2) return s2;
            }
        }
        if (output instanceof Map<?, ?> map) {
            for (String key : new String[]{"image", "output", "url"}) {
                Object v = map.get(key);
                if (v instanceof String s) return s;
            }
        }
        return null;
    }

    private byte[] downloadBytes(String url) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (body == null) throw new ExternalServiceException("Empty response downloading " + url);
        return body;
    }
}
