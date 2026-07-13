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
 * Calls Google's Nano Banana (Gemini Image) family via the Replicate
 * platform — same models as the direct Google API, but reuses the
 * existing REPLICATE_API_TOKEN so the user doesn't need to manage a
 * separate Google Cloud / AI Studio key.
 *
 * Models on Replicate as of 2026:
 *   google/nano-banana-2      — Gemini 3.1 Flash Image (recommended default)
 *   google/nano-banana-pro    — Gemini 3 Pro Image (highest quality, ~3x cost)
 *   google/nano-banana        — Gemini 2.5 Flash Image (cheapest, older)
 *
 * Honest caveat: this is image GENERATION, not pixel extraction.
 * Pixel alignment isn't guaranteed. Recent versions are
 * substantially better than older diffusion models but it's still worth
 * comparing this path's masks against the ADE20K and SAM paths on your
 * photo distribution.
 *
 * Configuration:
 *   replicate.nano-banana.enabled       — kill switch (default false)
 *   replicate.nano-banana.model         — owner/name (default google/nano-banana-2)
 *   replicate.nano-banana.model-version — pin a version hash for production
 *
 * Cost: ~$0.03-0.10 per mask depending on which Nano Banana variant.
 * One call per category = up to ~$0.30/upload on Pro, ~$0.10/upload
 * on Flash. User explicitly asked for quality over cost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicateNanoBananaSegmenter {

    private final RestTemplate restTemplate;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.nano-banana.model:google/nano-banana-2}")
    private String model;

    @Value("${replicate.nano-banana.model-version:}")
    private String modelVersion;

    @Value("${replicate.nano-banana.enabled:false}")
    private boolean enabled;

    /**
     * Output aspect ratio requested from the model. Gemini image models
     * generate into fixed aspect buckets by default, so WITHOUT this the
     * colour-coded mask can come back at a different aspect than the photo —
     * and every region mask is then systematically stretched or shifted off
     * the real surfaces. "match_input_image" pins the output to the photo's
     * own aspect. Blank = omit the parameter.
     */
    @Value("${replicate.nano-banana.aspect-ratio:match_input_image}")
    private String aspectRatio;

    /** Output resolution (e.g. 1K/2K/4K) when the model supports it; higher
     *  means finer mask edges. Blank (default) = omit and take the model's
     *  native output size. */
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
            log.debug("Nano Banana (Replicate) not configured — skipping");
            return Optional.empty();
        }
        try {
            log.info("Nano Banana (Replicate) [{}]: requesting mask for '{}'", model, surfaceDescription);

            String prompt = buildMaskPrompt(surfaceDescription);

            // Nano Banana on Replicate expects:
            //   prompt:       the text instruction
            //   image_input:  list of source image URLs (for editing context)
            //   output_format optional, "png" preferred for masks
            Map<String, Object> input = buildImageEditInput(prompt, imageUrl);

            String predictionId = startPrediction(input);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("Nano Banana (Replicate) prediction timed out or failed");
                return Optional.empty();
            }

            String maskUrl = extractOutputUrl(result.get("output"));
            if (maskUrl == null) {
                log.warn("Nano Banana (Replicate) returned no output URL");
                return Optional.empty();
            }

            byte[] bytes = downloadBytes(maskUrl);
            log.info("Nano Banana (Replicate) generated mask for '{}': {} bytes", surfaceDescription, bytes.length);
            return Optional.of(bytes);

        } catch (Exception e) {
            log.warn("Nano Banana (Replicate) failed for '{}': {}", surfaceDescription, e.getMessage());
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
     * more faithfully than generating a mask from scratch (Nano Banana warns
     * "pixel alignment isn't guaranteed" for generation), and the flat-fill
     * instruction — deliberately ignoring the photo's own shadows — keeps the
     * downstream colour-threshold split clean instead of punching holes wherever
     * a wall was shaded.
     *
     * <p>Big advantage over three separate single-category calls: 1 Replicate
     * call instead of 3 (3× faster, 3× cheaper), Gemini sees all categories
     * at once so a pixel can only belong to ONE category — no inter-mask
     * overlap.
     *
     * @param scene INDOOR rooms always get one designated accent wall (so the user
     *              has a wall to paint a highlight shade); exteriors keep the accent
     *              conditional on an actually-different-coloured secondary wall.
     */
    public Optional<byte[]> generateColorCodedMask(String imageUrl, ImageType scene) {
        if (!isConfigured()) {
            log.debug("Nano Banana (Replicate) not configured — skipping");
            return Optional.empty();
        }
        try {
            boolean forceAccent = scene == ImageType.INDOOR;
            log.info("Nano Banana (Replicate) [{}]: requesting COLOR-CODED mask (scene={}, forceAccent={})",
                    model, scene, forceAccent);

            Map<String, Object> input = buildImageEditInput(colorCodedPrompt(forceAccent), imageUrl);

            String predictionId = startPrediction(input);
            if (predictionId == null) return Optional.empty();

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                log.warn("Nano Banana color-coded prediction timed out");
                return Optional.empty();
            }
            String maskUrl = extractOutputUrl(result.get("output"));
            if (maskUrl == null) {
                log.warn("Nano Banana color-coded prediction had no output URL");
                return Optional.empty();
            }
            byte[] bytes = downloadBytes(maskUrl);
            log.info("Nano Banana color-coded mask: {} bytes", bytes.length);
            return Optional.of(bytes);
        } catch (Exception e) {
            log.warn("Nano Banana color-coded mask failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Single comprehensive prompt for the colour-blocked approach. Asks the
     * model to EDIT the photo, flooding each surface with one of four flat
     * colours (three paint categories plus a black "nothing" background).
     * Tested wording — be careful editing.
     *
     * The GREEN (accent) paragraph is the only part that varies by scene:
     * {@link #ACCENT_ALWAYS} (interiors) forces exactly one accent wall so the
     * user can paint a highlight shade; {@link #ACCENT_CONDITIONAL} (exteriors)
     * only marks an accent when a visibly different-coloured wall exists.
     */
    static String colorCodedPrompt(boolean forceAccent) {
        return COLOR_CODED_HEAD
             + (forceAccent ? ACCENT_ALWAYS : ACCENT_CONDITIONAL)
             + COLOR_CODED_TAIL;
    }

    private static final String COLOR_CODED_HEAD =
            "You are EDITING this exact room or building photograph — not generating a "
          + "new scene. Take the photo as given and repaint its surfaces into flat "
          + "blocks of colour, keeping every edge, corner and outline precisely where "
          + "it sits in the photo so the result lines up with the original "
          + "pixel-for-pixel. Output an image of the same exact dimensions.\n\n"
          + "Work in two steps. FIRST, mentally group the surfaces by their ROLE and "
          + "shape: which is the big main wall, which (if any) is a separate "
          + "accent/feature wall, and which are the trim pieces — the window and door "
          + "frames AND the projecting sunshade slabs above openings, the parapet "
          + "coping along the roof line, and the horizontal bands and ledges across "
          + "the facade. Actively look for those projecting slabs, copings and bands: "
          + "they are trim, not part of the main wall, even when they are the same "
          + "colour as it. Decide each group purely by what the surface IS — never by how light or "
          + "dark it happens to look. Lighting, sun and shadow must NOT change which "
          + "group a surface belongs to (a wall half in shadow is still that same one "
          + "wall). THEN flood each whole group with its single flat colour.\n\n"
          + "Fill each surface with ONE flat, solid, fully-saturated colour — a single "
          + "identical colour value across the whole surface, with NO shading, NO "
          + "gradient, NO texture and NO lighting variation inside a surface. Ignore "
          + "the photo's own shadows and highlights when filling: every pixel of one "
          + "surface gets the exact same pure colour. Use only these four colours:\n\n"
          + "- Paint the MAIN painted wall pure RED (#FF0000): the dominant flat "
          + "painted plaster/concrete/drywall someone would repaint with a single "
          + "colour (the largest painted area). Paint every painted wall RED except "
          + "the single accent wall described next.\n\n";

    /** Exterior/unknown: pick a feature wall by colour OR by architecture. A facade's
     *  feature volume (a projecting stair/lift tower, a tall vertical block framing
     *  the front) is usually painted the SAME colour as everything else in the photo,
     *  yet it is exactly the wall a designer picks out in a contrast shade — so the
     *  rule must not require an existing colour difference to mark it. */
    private static final String ACCENT_CONDITIONAL =
            "- Paint ONE ACCENT / feature wall pure GREEN (#00FF00). Choose it by "
          + "either of these, in order of preference:\n"
          + "   (a) a secondary painted wall that is ALREADY clearly a different "
          + "colour from the main wall — a feature wall, an accent strip, or a "
          + "perpendicular wall painted differently; or\n"
          + "   (b) a DISTINCT ARCHITECTURAL VOLUME that a designer would pick out "
          + "as the facade's feature even though it is currently painted the same "
          + "colour as the rest: a projecting or corner stair/lift tower, a tall "
          + "vertical block rising past the roof line, a porch/entrance mass, or a "
          + "prominent perpendicular wing. Paint that ENTIRE volume — all of its "
          + "visible faces, top to bottom — green.\n"
          + "Pick at most ONE feature (never two), and keep every remaining painted "
          + "wall red. Only if the facade is a single plain mass with neither (a) "
          + "nor (b) do you leave green out entirely.\n\n";

    /** Interior: always designate exactly one wall as the accent (highlight) wall. */
    private static final String ACCENT_ALWAYS =
            "- Paint exactly ONE ACCENT / feature wall pure GREEN (#00FF00), ALWAYS "
          + "chosen. Pick the single best wall to highlight: if one wall is already a "
          + "different colour, use that one; otherwise choose one prominent, mostly "
          + "unobstructed wall (typically the largest uninterrupted wall behind a "
          + "bed/sofa or the wall facing the camera) and paint that ENTIRE wall green. "
          + "Paint exactly ONE wall green and leave the remaining painted walls red. "
          + "Always designate one accent wall — never leave green out.\n\n";

    private static final String COLOR_CODED_TAIL =
            "- Paint TRIM, borders and frames pure BLUE (#0000FF): the narrow or "
          + "projecting elements a painter picks out in a contrasting trim colour. "
          + "You must find ALL of these and not miss them:\n"
          + "   * the flat sunshade / weather-shade slabs (chajja) that project "
          + "horizontally ABOVE windows and doors;\n"
          + "   * the parapet coping or cap running along the very top roof line, "
          + "and the top band of the parapet wall;\n"
          + "   * horizontal string-course bands, lintel bands and any decorative "
          + "banding running across the facade;\n"
          + "   * window sills, ledges, and the raised surrounds/borders framing "
          + "windows and doors;\n"
          + "   * window frames, door frames, skirting/baseboards and fascia under "
          + "the roof.\n"
          + "These projecting slabs, copings and bands are SEPARATE trim pieces even "
          + "though they are attached to the wall — mark them BLUE, NEVER red. Do not "
          + "swallow them into the main wall just because they are the same colour in "
          + "the photo; a real painter would pick them out, so they must be their own "
          + "colour here. (NOT the door panels themselves "
          + "and NOT metal railings — those are kept as fixed features (dark-brown "
          + "doors, charcoal-grey metalwork), so paint them BLACK, below.)\n\n"
          + "- Paint EVERYTHING ELSE pure BLACK (#000000): sky, clouds, ground, "
          + "dirt, road, sidewalk, vegetation, trees, vehicles, furniture, floor, "
          + "the door panels/leaves themselves, metal and iron railings (balcony "
          + "railings, staircase railings, handrails, balustrades), glass panes "
          + "inside windows, stone cladding, exposed "
          + "brick, ceramic tile, marble, wood, AC units, light fixtures, electrical "
          + "boxes, drainpipes, signage, mailboxes, decor, people — anything "
          + "that is NOT a paintable wall or trim surface. Doors and railings are "
          + "kept as fixed features (dark-brown doors, charcoal-grey metalwork), "
          + "so they belong here — never in a recoloured category.\n\n"
          + "RULES:\n"
          + "- Use ONLY these four exact colours: pure red (#FF0000), pure green "
          + "(#00FF00), pure blue (#0000FF), pure black (#000000). No other colours, "
          + "no in-between shades, no grey, no gradients, no shading.\n"
          + "- NEVER use WHITE (#FFFFFF) anywhere in the output. White is NOT one of "
          + "the four colours. A wall that is white in the photo is still recoloured "
          + "into its category colour: the accent/feature wall becomes pure green, "
          + "other painted walls pure red, trim pure blue. Do not leave any surface "
          + "white or photo-coloured.\n"
          + "- Every pixel is exactly ONE of the four colours. Never blend two.\n"
          + "- Keep every surface boundary exactly aligned with the photo (same "
          + "resolution, same edges, same object positions) so the colour blocks "
          + "trace the real surfaces precisely.\n"
          + "- Hard colour boundaries only — no anti-aliasing, no soft or feathered "
          + "edges.\n"
          + "- No text, watermarks, or annotations.\n"
          + "- Output: only the flat colour-blocked image.\n";

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
     * and resolution controls when configured. Both optional keys are safe to
     * send to the Nano Banana family; if a model variant rejects them,
     * {@link #startPrediction} retries once without them.
     */
    private Map<String, Object> buildImageEditInput(String prompt, String imageUrl) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("prompt", prompt);
        input.put("image_input", List.of(imageUrl));
        input.put("output_format", "png");
        if (aspectRatio != null && !aspectRatio.isBlank()) {
            input.put("aspect_ratio", aspectRatio.trim());
        }
        if (resolution != null && !resolution.isBlank()) {
            input.put("resolution", resolution.trim());
        }
        return input;
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
                log.warn("Nano Banana rejected optional inputs ({}), retrying without " +
                        "aspect_ratio/resolution: {}", e.getStatusCode(), e.getResponseBodyAsString());
                Map<String, Object> slim = new java.util.HashMap<>(input);
                slim.remove("aspect_ratio");
                slim.remove("resolution");
                try {
                    return doStartPrediction(slim);
                } catch (Exception retryError) {
                    log.warn("Nano Banana retry without optional inputs also failed: {}",
                            retryError.getMessage());
                    return null;
                }
            }
            log.warn("Failed to start Nano Banana prediction: {} {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("Failed to start Nano Banana prediction: {}", e.getMessage());
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
        log.debug("Nano Banana prediction started: id={}", id);
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
                log.warn("Nano Banana prediction terminal status: {} error: {}",
                        status, body.get("error"));
                return null;
            }
        }
        return null;
    }

    /**
     * Nano Banana output on Replicate can be a single URL string, a list
     * of URLs, or a dict — depends on the model variant. Handle all three.
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
