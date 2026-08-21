package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.ai.ReplicateImageEditor;
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
 * Generates the colour-coded wall mask by asking Replicate's Nano Banana Pro
 * ({@code google/nano-banana-pro}, i.e. Gemini 3 Pro Image) to flood each
 * paintable surface with a flat category colour.
 *
 * Honest caveat: this is generative image EDITING, not pixel extraction.
 * Pixel alignment isn't guaranteed — the model repaints the photo rather than
 * tracing it, so its colour blocks land a little off the surfaces they
 * describe, and further off again whenever it rounds the output to one of its
 * aspect buckets. {@link MaskAligner}, downstream, measures that offset
 * against the canvas and corrects it before the masks are stored.
 *
 * <h2>Whose request schema</h2>
 *
 * The request body is built by {@link ReplicateImageEditor}, keyed off the model id, for
 * the same reason the cleaner's is: the families do not agree on the key the photo
 * arrives under, and sending Nano Banana's {@code image_input} to FLUX gets a 422 and no
 * mask. This used to be written inline here because there was only ever one model to
 * talk to. There isn't — an ADMIN can run a single mask on any model in
 * {@code AiModelCatalogue} to see how it fills to an edge, and a sibling tier can be set
 * in config — so the schema is looked up rather than assumed.
 *
 * Configuration:
 *   replicate.nano-banana.enabled       — kill switch (default false)
 *   replicate.nano-banana.model         — owner/name (default google/nano-banana-pro)
 *   replicate.nano-banana.model-version — pin a version hash for production
 *
 * Cost: ~$0.10 per mask. One colour-coded call per upload. User explicitly
 * asked for quality over cost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicateMaskSegmenter {

    private final RestTemplate restTemplate;
    /** Owns the per-family request body; see the class doc. */
    private final ReplicateImageEditor imageEditor;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.nano-banana.model:google/nano-banana-pro}")
    private String model;

    /**
     * The models tried after {@link #model}, in order — comma-separated Replicate ids.
     *
     * <p>Deliberately the same family, unlike the cleaner's chain, and that difference is
     * the whole design. The clean only has to produce a plausible photo, so any competent
     * editor will do and swapping families is free. A mask has to come back as four flat
     * primaries pinned to the photo's own edges, and only the Gemini image models follow
     * that instruction closely enough for {@code splitColorCodedMask} to read the result
     * — FLUX and Seedream return a beautifully shaded picture that the split then throws
     * away. So the fallback is the sibling TIER (Nano Banana 2), which speaks the same
     * schema and obeys the same prompt, rather than a different family that would fail
     * differently and more expensively.
     */
    @Value("${replicate.nano-banana.fallback-models:google/nano-banana-2}")
    private String fallbackModels;

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
     * aspect. Blank = omit the parameter.
     */
    @Value("${replicate.nano-banana.aspect-ratio:match_input_image}")
    private String aspectRatio;

    /** Output resolution (1K/2K/4K); higher means finer mask edges. Blank
     *  (default) = omit and take the model's native output size. */
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

    /** The configured model, for a caller that wants to name it in a log or a response. */
    public String model() {
        return model;
    }

    /**
     * The whole mask chain in order: the configured model, then its fallbacks.
     *
     * <p>Exposed rather than looped over in here because a mask can fail in two quite
     * different ways and only the caller can see both. This class knows when the model
     * refused or timed out; {@link SegmentationService} knows when an image DID come back
     * and turned out to be unusable — no red main wall, off-palette, below the noise
     * floor — which is the more common dud by a distance. Retrying only what this class
     * can see would leave the frequent failure unretried, so the chain is driven from
     * there, over both.
     */
    public java.util.List<String> modelChain() {
        java.util.LinkedHashSet<String> chain = new java.util.LinkedHashSet<>();
        if (model != null && !model.isBlank()) chain.add(model.trim());
        if (fallbackModels != null && !fallbackModels.isBlank()) {
            for (String id : fallbackModels.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) chain.add(trimmed);
            }
        }
        return java.util.List.copyOf(chain);
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
        return generateColorCodedMask(imageUrl, scene, null);
    }

    /**
     * @param requestedModel the exact model to ask — a link in the chain
     *                       ({@link #modelChain()}) or an ADMIN's per-run pin, both
     *                       already checked against {@code AiModelCatalogue}. Null takes
     *                       the configured {@link #model}.
     *
     *                       <p>A pinned {@code model-version} is applied only when this
     *                       resolves to the CONFIGURED model. A version hash identifies
     *                       one release of one model, so carrying it onto any other asks
     *                       Replicate for something that does not exist — which is why
     *                       the test is "is this the configured model" rather than "did
     *                       the caller pass a name": the chain names the configured model
     *                       explicitly on its first link, and that link should still get
     *                       the pin a production deployment set.
     */
    public Optional<byte[]> generateColorCodedMask(String imageUrl, ImageType scene,
                                                   String requestedModel) {
        String modelId = (requestedModel == null || requestedModel.isBlank())
                ? model : requestedModel.trim();
        boolean overridden = modelId != null && !modelId.equals(model);
        boolean ready = enabled
                && replicateApiToken != null && !replicateApiToken.isBlank()
                && modelId != null && !modelId.isBlank();
        if (!ready) {
            log.debug("Mask segmenter not configured — skipping");
            return Optional.empty();
        }
        try {
            boolean forceAccent = scene == ImageType.INDOOR;
            log.info("Mask segmenter [{}]: requesting COLOR-CODED mask (scene={}, forceAccent={}{})",
                    modelId, scene, forceAccent, overridden ? ", not the configured model" : "");

            Map<String, Object> input = buildImageEditInput(
                    modelId, colorCodedPrompt(forceAccent), imageUrl);

            String predictionId = startPrediction(modelId, input, overridden);
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
     * <p>Three invariants matter most:
     * <ul>
     *   <li><b>Bare masonry is a wall, not cladding.</b> The cladding rule
     *   ("brick/stone/tile faces are BLACK") used to be unconditional, so a
     *   room or facade photographed mid-construction — every wall raw brick or
     *   blockwork — came back an almost entirely black mask, no main wall, and
     *   the run failed. Decorative cladding in a finished space is still
     *   BLACK; an unplastered construction shell is classified by role
     *   (red/green) because it is about to be plastered and painted.</li>
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
          + "All flat plaster/concrete/drywall/masonry wall planes — the surfaces a "
          + "painter would roll in the main body colour (the largest painted area). "
          + "Outdoors this includes painted columns and porch pillars, solid masonry "
          + "balcony parapet fronts, and painted compound/boundary walls and gate "
          + "pillars. Indoors it is EVERY room wall plane, filled corner to corner "
          + "and floor to ceiling — including the strips of wall above and beside a "
          + "door or window, and the returns/reveals inside a deep opening. Every "
          + "wall is RED unless it qualifies as the single accent wall described "
          + "next.\n\n"
          + "A wall counts here whether it is freshly painted, dirty, stained, "
          + "shadowed, or still a bare unplastered shell (see the construction-"
          + "masonry rule below). Its ROLE is what decides its colour, never its "
          + "current finish.\n\n";

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
          + "the roof, and caps on compound-wall pillars.\n"
          + "- Indoors the trim is usually just these: the skirting board along "
          + "the foot of the walls, the frames/architraves around doors and "
          + "windows, the window sill, and a cornice where wall meets ceiling. "
          + "If a room has none of them fitted yet, output NO blue at all rather "
          + "than inventing a band.\n\n"
          + "These projecting slabs, copings and bands are SEPARATE trim pieces "
          + "even though they are attached to the wall and may be the same colour "
          + "in the photo. A real painter picks them out in a contrast colour, so "
          + "they are BLUE — never swallowed into the red wall. Tie-breaker: if "
          + "you are unsure whether a narrow band, ledge or projecting slab is "
          + "wall or trim, it is TRIM (blue). But metalwork is NEVER trim: a "
          + "railing or grille mounted on a parapet, balcony or staircase is NOT "
          + "blue — only the masonry band or coping beneath it is; the railing "
          + "itself is BLACK, below.\n\n"
          + "TRIM IS DRAWN AT ITS TRUE WIDTH ONLY. Each blue element must be "
          + "exactly as wide as the physical trim piece itself — the band a "
          + "painter would cut with masking tape. Never thicken a band, never "
          + "halo it, and never draw blue outlines along wall edges, wall-to-wall "
          + "junctions, inside corners or shadow lines: a shadow under a slab is "
          + "NOT trim, and the joint where two red walls meet is NOT trim. Large "
          + "flat faces are never blue — the sloping side face of a staircase "
          + "parapet is wall (RED); only its narrow top coping band is blue.\n\n"
          + "AN OPENING IS NOT TRIM. Never flood a whole doorway or window "
          + "opening with blue. Only the narrow frame band AROUND the opening is "
          + "blue; what the opening contains is BLACK — the glass and any grille "
          + "or bars in a window, the door leaf, and the dark void of an empty "
          + "doorway or an unframed opening in an unfinished wall. An opening "
          + "with no frame fitted yet gets no blue at all: its void is black and "
          + "the masonry around it is wall.\n\n"
          + "4. EVERYTHING ELSE → BLACK (#000000)\n\n"
          + "Sky, clouds, ground, soil, road, sidewalk, vegetation, trees, "
          + "vehicles, people, furniture, floors, ceilings; the door LEAVES/"
          + "panels themselves, gates, and rolling/roller shutters, garage and "
          + "shop-front shutters and their guide rails; all metalwork; glass "
          + "panes inside windows; DECORATIVE stone cladding, exposed-brick "
          + "feature walls, ceramic tile, marble and wood finishes in an "
          + "otherwise finished space (a raw unplastered brick or blockwork "
          + "wall is NOT this — see the construction-masonry rule below); "
          + "AC units, drainpipes, wires, light fixtures, "
          + "electrical boxes, meters, water tanks, signage, mailboxes, decor. "
          + "Doors and railings are kept as fixed features in the real scheme "
          + "(dark-brown doors, charcoal-grey metalwork), so here they belong "
          + "in BLACK — never in red, green or blue.\n\n"
          + "CLADDING IS A MATERIAL, NOT PAINT — this overrides the wall rules: "
          + "any wall FACE deliberately FINISHED in decorative stone, brick, tile, "
          + "wood or similar cladding is BLACK, even when it is a large flat "
          + "wall-shaped surface. A painter does not roll over a finished cladding "
          + "panel, so it is NEVER the main wall (red) and NEVER the accent "
          + "(green): leave it BLACK.\n\n"
          + "BARE CONSTRUCTION MASONRY IS A WALL, NOT CLADDING — this exception "
          + "overrides the cladding rule and is the single most common way this "
          + "task is got wrong. A wall of raw unplastered brick or blockwork, bare "
          + "cement render, or patchy half-applied plaster is a wall waiting to be "
          + "plastered and painted, so it is classified by its ROLE exactly like "
          + "any other wall: RED for body walls, GREEN for the one accent wall. "
          + "Tell the two apart by the room as a whole, not by the texture alone:\n"
          + "- DECORATIVE cladding (BLACK) sits in an otherwise FINISHED space — "
          + "neat and deliberate, laid in an even pattern with clean edges, "
          + "confined to one face while the walls around it are smooth and "
          + "painted, and surrounded by finished flooring, skirting and trim.\n"
          + "- CONSTRUCTION masonry (RED/GREEN) is a shell still being built — "
          + "rough mortar joints, dust and debris, chipped or ragged edges, "
          + "conduit chases and junction boxes let into the wall, unfinished "
          + "openings with no frames or shutters, a bare concrete ceiling and a "
          + "raw cement floor, and usually EVERY wall in the frame in the same "
          + "state.\n"
          + "When a whole room or facade is bare brick or blockwork like that, it "
          + "is under construction, not clad: colour its walls RED and GREEN. "
          + "Blacking out an entire unfinished room is a FAILED mask — the user "
          + "photographs these rooms precisely to see them painted.\n\n"
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
          + "- The image is NOT mostly black. Every wall plane in the photo "
          + "carries a colour — red, or green for the single accent — including "
          + "walls that are bare unplastered brick, blockwork or cement. If large "
          + "wall areas came back black, you misread a construction shell as "
          + "decorative cladding: go back and colour those walls.\n"
          + "- Compare against the photo: every railing, grille and gate is "
          + "still there, black, in its exact place — none missing, none "
          + "absorbed into a red, green or blue area.\n"
          + "- GREEN follows the accent rule above — never two or more greens; "
          + "when a green is present, the whole plane/volume is filled across "
          + "every visible face and its edges land on true corners, recesses or "
          + "plane changes — never mid-wall.\n"
          + "- Every chajja, parapet coping, band, sill, frame and border is "
          + "traced in BLUE — none left red — and every blue piece sits at its "
          + "true physical width: no thick blue outlines hugging wall edges, no "
          + "wall junctions or shadow bands marked blue.\n"
          + "- Door panels, railings, rolling/garage shutters, textured "
          + "stone/brick/tile cladding, glass, sky, ground and vegetation are "
          + "all BLACK — none painted red, green or blue.\n"
          + "- Zero white, grey or off-palette pixels. Zero gradients, zero soft "
          + "edges.\n"
          + "- Output has the same dimensions as the input, with every edge "
          + "aligned pixel-for-pixel.\n\n"
          + "Return only the finished flat colour-block image.\n";

    /**
     * The image-edit body for {@code modelId}, plus the OPTIONAL aspect-ratio and
     * resolution controls when configured. {@code aspect_ratio} with
     * "match_input_image" is what keeps the mask pinned to the photo's exact
     * aspect. If a model variant rejects an optional key, {@link #startPrediction}
     * retries once without them.
     *
     * <p>Delegated to {@link ReplicateImageEditor} so which key the photo goes under is
     * decided by the model's family rather than assumed to be Nano Banana's.
     * {@code output_format} stays PNG: the split downstream reads exact RGB values, and
     * JPEG's ringing around a hard colour boundary is precisely what it must not see.
     */
    private Map<String, Object> buildImageEditInput(String modelId, String prompt, String imageUrl) {
        return imageEditor.buildInput(
                modelId, prompt, List.of(imageUrl), aspectRatio, resolution, "png");
    }

    private String startPrediction(String modelId, Map<String, Object> input, boolean overridden) {
        try {
            return doStartPrediction(modelId, input, overridden);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 400/422 usually means an input the model version doesn't know.
            // Drop the optional tuning keys and retry once — a mask at the
            // model's default aspect/resolution beats no mask at all.
            boolean hadOptional = ReplicateImageEditor.OPTIONAL_KEYS.stream().anyMatch(input::containsKey);
            if (hadOptional && (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 422)) {
                log.warn("Mask model {} rejected optional inputs ({}), retrying with prompt + " +
                        "image only: {}", modelId, e.getStatusCode(), e.getResponseBodyAsString());
                Map<String, Object> slim = new java.util.HashMap<>(input);
                ReplicateImageEditor.OPTIONAL_KEYS.forEach(slim::remove);
                try {
                    return doStartPrediction(modelId, slim, overridden);
                } catch (Exception retryError) {
                    log.warn("Mask model retry without optional inputs also failed: {}",
                            retryError.getMessage());
                    return null;
                }
            }
            log.warn("Failed to start mask prediction on {}: {} {}",
                    modelId, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("Failed to start mask prediction on {}: {}", modelId, e.getMessage());
            return null;
        }
    }

    private String doStartPrediction(String modelId, Map<String, Object> input, boolean overridden) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        // The pinned version belongs to the CONFIGURED model. An admin testing a
        // different one gets the model endpoint instead — see generateColorCodedMask.
        boolean hasPinnedVersion = !overridden && modelVersion != null && !modelVersion.isBlank();
        Map<String, Object> body = hasPinnedVersion
                ? Map.of("version", modelVersion, "input", input)
                : Map.of("input", input);
        String endpoint = hasPinnedVersion
                ? REPLICATE_BASE + "/predictions"
                : REPLICATE_BASE + "/models/" + modelId + "/predictions";

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
     * Nano Banana returns a list of URLs, but Replicate's envelope also allows
     * a bare URL string or a dict depending on the model version. Handle all
     * three so a schema change doesn't break the mask.
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
