package com.gridstore.huevista.project.service;

import com.gridstore.huevista.image.model.ImageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pre-analyzes the photograph with Claude so we can drive SAM 2 with point
 * prompts instead of relying on grounded_sam's flaky text-matching. Claude
 * returns normalized image-space coordinates for each surface category
 * (main wall, accent wall, trim) plus negative points for things to exclude
 * (stone, tile, doors, fixtures). We feed those directly to SAM 2 and let
 * it produce the actual masks — SAM 2's edges are dramatically tighter than
 * anything text-grounded.
 *
 * Defaults to Sonnet 4.6 because spatial coordinate output is the hardest
 * thing for vision models and Haiku's accuracy is too low for it.
 *
 * Returns Optional.empty() on any failure so the caller can fail clearly
 * rather than feed SAM 2 garbage coordinates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WallSceneAnalyzer {

    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;

    @Value("${app.claude.api-key:}")
    private String apiKey;

    /**
     * Model for scene point analysis. Sonnet by default — Haiku consistently
     * mislocates points by 10–15% which is enough to send SAM 2 off the wall.
     */
    @Value("${app.claude.scene-analysis-model:claude-sonnet-4-6}")
    private String model;

    @Value("${app.segmentation.scene-analysis.enabled:true}")
    private boolean enabled;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_POINTS_PER_CATEGORY = 8;

    public Optional<WallSceneAnalysis> analyze(String imageUrl, ImageType imageType) {
        if (!enabled) {
            log.debug("Scene analysis disabled by config");
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Claude API key not set — scene analysis disabled");
            return Optional.empty();
        }
        if (imageType == null) imageType = ImageType.INDOOR;

        try {
            byte[] resized = downloadAndResize(imageUrl);
            String base64 = Base64.getEncoder().encodeToString(resized);
            String prompt = buildPrompt(imageType);

            Map<String, Object> imageBlock = Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", "image/jpeg",
                            "data", base64
                    )
            );
            Map<String, Object> textBlock = Map.of("type", "text", "text", prompt);

            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 1500,
                    "messages", List.of(
                            Map.of("role", "user", "content", List.of(imageBlock, textBlock))
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> response = restTemplate.exchange(
                    CLAUDE_API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) response.getBody().get("content");
            if (content == null || content.isEmpty()) {
                log.warn("Scene analysis: Claude returned empty content");
                return Optional.empty();
            }
            String text = ((String) content.get(0).get("text")).trim();
            String json = stripCodeFences(text);
            JsonNode root = objectMapper.readTree(json);

            boolean paintable = root.path("paintable").asBoolean(true);
            String material = textOrNull(root.path("wall_material"));
            String notes = textOrNull(root.path("notes"));

            List<WallSceneAnalysis.Point> mainWall = parsePoints(root.path("main_wall_points"));
            List<WallSceneAnalysis.Point> accentWall = parsePoints(root.path("accent_wall_points"));
            List<WallSceneAnalysis.Point> trim = parsePoints(root.path("trim_points"));
            List<WallSceneAnalysis.Point> exclude = parsePoints(root.path("exclude_points"));

            log.info("Scene analysis [{}]: paintable={} material='{}' main={} accent={} trim={} exclude={}",
                    imageType, paintable, material,
                    mainWall.size(), accentWall.size(), trim.size(), exclude.size());

            return Optional.of(new WallSceneAnalysis(
                    paintable, material, mainWall, accentWall, trim, exclude, notes));

        } catch (Exception e) {
            log.warn("Scene analysis failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Set-of-Mark classification with TWO images: the original photo and
     * the same photo overlaid with numbered candidate masks. Claude can
     * cross-reference — "in image 1 this region is clearly cream plaster,
     * in image 2 that region is masks 4, 7, 12, 15, all going into
     * main_wall." Returns Optional.empty() on any failure so the caller
     * can give up cleanly instead of producing garbage masks.
     */
    public Optional<MaskClassification> classifyMasks(byte[] originalJpegBytes,
                                                      byte[] annotatedJpegBytes,
                                                      ImageType imageType, int maskCount) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        if (annotatedJpegBytes == null || annotatedJpegBytes.length == 0 || maskCount <= 0) {
            return Optional.empty();
        }
        try {
            String prompt = buildClassifyPrompt(imageType, maskCount);

            List<Object> content = new ArrayList<>();
            if (originalJpegBytes != null && originalJpegBytes.length > 0) {
                content.add(Map.of("type", "text",
                        "text", "Image 1: the ORIGINAL photograph — use this to identify what each surface actually is."));
                content.add(Map.of(
                        "type", "image",
                        "source", Map.of(
                                "type", "base64",
                                "media_type", "image/jpeg",
                                "data", Base64.getEncoder().encodeToString(originalJpegBytes)
                        )
                ));
                content.add(Map.of("type", "text",
                        "text", "Image 2: the SAME photograph with " + maskCount + " numbered candidate masks overlaid in different colors. Read the numbers to classify."));
            }
            content.add(Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", "image/jpeg",
                            "data", Base64.getEncoder().encodeToString(annotatedJpegBytes)
                    )
            ));
            content.add(Map.of("type", "text", "text", prompt));

            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 1500,
                    "messages", List.of(
                            Map.of("role", "user", "content", content)
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> response = restTemplate.exchange(
                    CLAUDE_API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> responseBlocks =
                    (List<Map<String, Object>>) response.getBody().get("content");
            if (responseBlocks == null || responseBlocks.isEmpty()) return Optional.empty();
            String text = ((String) responseBlocks.get(0).get("text")).trim();
            JsonNode root = objectMapper.readTree(stripCodeFences(text));

            List<Integer> main = parseIntList(root.path("main_wall"), maskCount);
            List<Integer> accent = parseIntList(root.path("accent_wall"), maskCount);
            List<Integer> trim = parseIntList(root.path("trim"), maskCount);
            List<Integer> exclude = parseIntList(root.path("exclude"), maskCount);
            boolean paintable = root.path("paintable").asBoolean(true);
            String material = textOrNull(root.path("wall_material"));
            String notes = textOrNull(root.path("notes"));

            log.info("Mask classification [{}]: paintable={} material='{}' main={} accent={} trim={} exclude={}",
                    imageType, paintable, material, main, accent, trim, exclude);
            return Optional.of(new MaskClassification(main, accent, trim, exclude, paintable, material, notes));

        } catch (Exception e) {
            log.warn("Mask classification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private List<Integer> parseIntList(JsonNode node, int maxValue) {
        List<Integer> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode n : node) {
            int v = n.asInt(-1);
            if (v >= 1 && v <= maxValue && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private String buildClassifyPrompt(ImageType imageType, int maskCount) {
        boolean outdoor = imageType == ImageType.OUTDOOR;
        String typeHint = outdoor ? "OUTDOOR (building facade / exterior)" : "INDOOR (room / interior)";
        String mainHint = outdoor
                ? "the largest visible painted wall of the building facade (the dominant cream/beige/colored plaster surface, NOT brick, stone, tile, or siding)"
                : "the largest visible painted interior wall (the dominant flat painted surface, NOT tiled, wallpapered, or wood-paneled)";
        String accentHint = outdoor
                ? "a secondary paintable facade section in a different color (e.g. a band, an entry porch surround, a parapet face)"
                : "a feature wall in a different color or a perpendicular wall";
        String trimHint = outdoor
                ? "window frames, door frames, fascia under the roof, soffit, parapet edges, balcony railings, decorative banding"
                : "window frames, door frames, baseboards / skirting, crown molding, picture rails, wainscoting";

        return ("You are looking at an %s photograph. The second image is the SAME photo with\n"
                + "%d numbered candidate masks overlaid in different colors. Use the first image\n"
                + "(unannotated original) to see what each surface actually is; use the second\n"
                + "image (annotated) to read the mask numbers.\n\n"
                + "WHAT WE ARE DOING\n"
                + "We are repainting this building. The result is computed by SUBTRACTION:\n"
                + "  main_wall = (everything in the photo) – exclude – accent – trim\n"
                + "So your most important job is to identify EVERY mask that should NOT be\n"
                + "painted at all — those go into `exclude`. Whatever you leave out of exclude,\n"
                + "accent, and trim will be painted with the main color.\n\n"
                + "EXCLUDE — list mask numbers covering ANY of these. This is the critical list:\n"
                + "  * Doors, garage doors, gates, door panels\n"
                + "  * Windows, window glass, balcony glass, glazing\n"
                + "  * Stone cladding, exposed brick, brick parapet, stone wall, stone column\n"
                + "  * Ceramic tile, marble, granite, wallpaper, wood paneling, vinyl/metal siding\n"
                + "  * AC units, exhaust fans, ducts, vents, electrical meters/boxes, drainpipes,\n"
                + "    gutters, downspouts, satellite dishes, antennas, light fixtures, sconces,\n"
                + "    light switches, electrical outlets, security cameras, junction boxes\n"
                + "  * Mirrors, picture frames, paintings, TVs, signage, mailboxes, lamp posts\n"
                + "  * Roof, roof tile, chimney (NOT roof eaves which are trim)\n"
                + "  * Trees, bushes, plants, vines, grass, lawn\n"
                + "  * Cars, motorcycles, bicycles, ladders\n"
                + "  * Sky, clouds, distant buildings, fences (chain-link or metal)\n"
                + "  * Ground, dirt, gravel, road, sidewalk, driveway, pavement\n"
                + "  * People, animals\n"
                + "  * Decorative wrought iron, balcony railings (railings only — the wall\n"
                + "    behind them is paintable and goes into main_wall)\n\n"
                + "ACCENT_WALL — usually empty []. Only fill if there is a clearly DIFFERENT\n"
                + "color painted wall (e.g. a feature wall painted dark on a cream facade, or\n"
                + "a perpendicular wall of a different shade). Don't split one cream wall into\n"
                + "main + accent based on shadows — that's still all main_wall.\n\n"
                + "TRIM — %s. Optional; empty [] if not clearly visible.\n\n"
                + "MAIN_WALL — every mask covering %s. Best practice: list the obvious main wall\n"
                + "pieces here, and let the segmenter automatically include anything you don't\n"
                + "explicitly list elsewhere. Including a mask here is a positive signal but not\n"
                + "required — the segmenter defaults uncategorized masks to main_wall.\n\n"
                + "RULES OF THUMB\n"
                + "- Be GENEROUS with exclude. If you're not sure whether something is paintable,\n"
                + "  EXCLUDE it — it's worse to paint over an AC unit than to leave a wall fragment\n"
                + "  unpainted (the user can click-segment to add it back).\n"
                + "- A mask number belongs to AT MOST ONE list. Same mask can't be in exclude\n"
                + "  AND main_wall.\n"
                + "- Every mask 1..%d should appear in exactly one list. If you genuinely don't\n"
                + "  know what a mask covers, omit it — the segmenter will default it to main_wall.\n\n"
                + "ALSO RETURN\n"
                + "- paintable: false ONLY if NO paintable wall is visible (whole facade is brick\n"
                + "  or tile or wood). Otherwise true.\n"
                + "- wall_material: short description of the dominant wall material.\n"
                + "- notes: one sentence summary.\n\n"
                + "Return ONLY this JSON. No markdown fences, no prose before or after:\n\n"
                + "{\n"
                + "  \"paintable\": <true|false>,\n"
                + "  \"wall_material\": \"<string>\",\n"
                + "  \"main_wall\": [<mask numbers>],\n"
                + "  \"accent_wall\": [<mask numbers>],\n"
                + "  \"trim\": [<mask numbers>],\n"
                + "  \"exclude\": [<mask numbers>],\n"
                + "  \"notes\": \"<string>\"\n"
                + "}\n").formatted(typeHint, maskCount, trimHint, mainHint, maskCount);
    }

    private List<WallSceneAnalysis.Point> parsePoints(JsonNode node) {
        List<WallSceneAnalysis.Point> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode item : node) {
            if (!item.isObject()) continue;
            double x = item.path("x").asDouble(-1);
            double y = item.path("y").asDouble(-1);
            WallSceneAnalysis.Point p = new WallSceneAnalysis.Point(x, y);
            if (!p.isValid()) continue;
            out.add(p);
            if (out.size() >= MAX_POINTS_PER_CATEGORY) break;
        }
        return out;
    }

    private String buildPrompt(ImageType imageType) {
        boolean outdoor = imageType == ImageType.OUTDOOR;
        String typeHint = outdoor ? "OUTDOOR (building facade / exterior)" : "INDOOR (room / interior)";
        String trimExamples = outdoor
                ? "window frames, door frames, fascia under the roof, soffit, parapet edges, balcony railings"
                : "window frames, door frames, baseboard/skirting along the floor, crown molding at the ceiling, wainscoting";
        String excludeExamples = outdoor
                ? "stone cladding, exposed brick, ceramic tile, glass windows, garage door, AC unit, drainpipe, satellite dish, sky, trees, parked cars, fence, ground"
                : "stone cladding, exposed brick, ceramic tile, marble, wallpaper, wood paneling, doors, windows, mirrors, TVs, picture frames, light switches, electrical outlets, AC vent, sconces, lamps, furniture, curtains";

        return ("You are analyzing an %s photograph for an automated paint visualization app.\n"
                + "Identify the paintable surfaces and their locations as normalized (x, y) coordinates.\n\n"
                + "COORDINATE SYSTEM:\n"
                + "- x ∈ [0.0, 1.0] — 0.0 = LEFT edge, 1.0 = RIGHT edge\n"
                + "- y ∈ [0.0, 1.0] — 0.0 = TOP edge, 1.0 = BOTTOM edge\n"
                + "- Place every point INSIDE its surface (not on the boundary).\n"
                + "- Spread multiple points across the surface so SAM 2 captures the full extent.\n\n"
                + "CATEGORIES:\n"
                + "1. main_wall_points — 2 to 4 points inside the LARGEST visible paintable wall.\n"
                + "   The main wall is the dominant flat painted surface (plaster/drywall/concrete).\n"
                + "2. accent_wall_points — 1 to 3 points inside a SECONDARY paintable wall, if one\n"
                + "   exists (a perpendicular wall, a feature wall, a wall of a clearly different\n"
                + "   color). Return an empty array [] if the photo has no distinct accent wall.\n"
                + "3. trim_points — 2 to 5 points on visible trim/border surfaces: %s.\n"
                + "   Return [] if no trim is visible.\n"
                + "4. exclude_points — 2 to 8 points on things NEAR or WITHIN the wall area that\n"
                + "   MUST NOT be painted: %s. These become NEGATIVE points for SAM 2.\n\n"
                + "ALSO RETURN:\n"
                + "- paintable: true if the visible walls can be repainted at all. false ONLY if the\n"
                + "  entire visible wall surface is non-paintable (full brick wall, full tiled bathroom,\n"
                + "  full wood-paneled room, vinyl siding facade).\n"
                + "- wall_material: short description of the dominant wall material.\n"
                + "- notes: one-sentence summary surfaced to the user if paintable=false.\n\n"
                + "Return ONLY this JSON. No markdown fences. No prose before or after.\n\n"
                + "{\n"
                + "  \"paintable\": <true|false>,\n"
                + "  \"wall_material\": \"<string>\",\n"
                + "  \"main_wall_points\": [{\"x\": 0.5, \"y\": 0.4}, ...],\n"
                + "  \"accent_wall_points\": [{\"x\": 0.2, \"y\": 0.5}, ...],\n"
                + "  \"trim_points\": [{\"x\": 0.3, \"y\": 0.7}, ...],\n"
                + "  \"exclude_points\": [{\"x\": 0.15, \"y\": 0.6}, ...],\n"
                + "  \"notes\": \"<string>\"\n"
                + "}\n").formatted(typeHint, trimExamples, excludeExamples);
    }

    private byte[] downloadAndResize(String imageUrl) throws Exception {
        byte[] original = restTemplate.getForObject(URI.create(imageUrl), byte[].class);
        if (original == null || original.length == 0) {
            throw new RuntimeException("Empty image response from " + imageUrl);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(original))
                .size(1024, 1024)
                .keepAspectRatio(true)
                .outputFormat("jpeg")
                .outputQuality(0.85)
                .toOutputStream(out);
        return out.toByteArray();
    }

    private static String stripCodeFences(String s) {
        s = s.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        int open = s.indexOf('{');
        int close = s.lastIndexOf('}');
        if (open >= 0 && close > open) {
            s = s.substring(open, close + 1);
        }
        return s.trim();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String t = node.asText("").trim();
        return t.isEmpty() ? null : t;
    }
}
