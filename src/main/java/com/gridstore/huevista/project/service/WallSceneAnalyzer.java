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
     * Set-of-Mark classification: takes the original photo overlaid with
     * numbered candidate masks (produced by SAM 2 auto-segmentation) and
     * asks Claude which numbers belong to MAIN_WALL, ACCENT_WALL, and TRIM.
     * Returns Optional.empty() on any failure so the caller can give up
     * cleanly instead of producing garbage masks.
     */
    public Optional<MaskClassification> classifyMasks(byte[] annotatedJpegBytes, ImageType imageType, int maskCount) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        if (annotatedJpegBytes == null || annotatedJpegBytes.length == 0 || maskCount <= 0) {
            return Optional.empty();
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(annotatedJpegBytes);
            String prompt = buildClassifyPrompt(imageType, maskCount);

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
                    "max_tokens", 1200,
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
            if (content == null || content.isEmpty()) return Optional.empty();
            String text = ((String) content.get(0).get("text")).trim();
            JsonNode root = objectMapper.readTree(stripCodeFences(text));

            List<Integer> main = parseIntList(root.path("main_wall"), maskCount);
            List<Integer> accent = parseIntList(root.path("accent_wall"), maskCount);
            List<Integer> trim = parseIntList(root.path("trim"), maskCount);
            boolean paintable = root.path("paintable").asBoolean(true);
            String material = textOrNull(root.path("wall_material"));
            String notes = textOrNull(root.path("notes"));

            log.info("Mask classification [{}]: paintable={} material='{}' main={} accent={} trim={}",
                    imageType, paintable, material, main, accent, trim);
            return Optional.of(new MaskClassification(main, accent, trim, paintable, material, notes));

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

        return ("You are looking at an %s photograph with %d numbered candidate masks overlaid on it.\n"
                + "Each numbered colored region is one mask. Read the numbers and decide which masks\n"
                + "belong to each paint category.\n\n"
                + "CRITICAL: SAM 2 OVER-SPLITS WALLS\n"
                + "Walls of the same color are typically split across MANY masks — sometimes 5 to 15\n"
                + "separate numbers — because SAM separates them at shadows, color gradients, edges,\n"
                + "and architectural joints. You MUST list EVERY mask number that covers the same\n"
                + "painted wall surface, not just the largest piece. If a cream wall is split into\n"
                + "8 masks, all 8 numbers go into main_wall. Coverage matters more than tidiness.\n\n"
                + "RULES:\n"
                + "- main_wall: ALL mask numbers covering %s. Include EVERY piece — corners, soffits,\n"
                + "  sections above/below windows, narrow strips next to doors, the wall behind the\n"
                + "  balcony — anything that is the same painted-plaster surface. Typically 4–15\n"
                + "  numbers for a real-world photo; rarely fewer than 3.\n"
                + "- accent_wall: ALL numbers covering %s. Include every piece of that secondary\n"
                + "  surface. Empty [] if there's no distinct second color/wall.\n"
                + "- trim: ALL numbers covering %s. Include every visible trim piece — every window\n"
                + "  frame, every door frame, every railing baluster.\n"
                + "- HARD EXCLUDE — these are absolute, never include them in any category:\n"
                + "    * sky, clouds, atmospheric haze (even if cream/beige at sunset)\n"
                + "    * ground, dirt, soil, gravel, road, sidewalk, driveway, lawn, grass\n"
                + "    * the largest mask in the photo if it covers > 1/3 of the frame — that's\n"
                + "      almost always the sky or a background mask, not the wall\n"
                + "    * any mask whose entire visible area is in the TOP THIRD of the photo\n"
                + "      unless it is clearly part of the building (parapet, upper floor wall)\n"
                + "    * any mask whose entire visible area is in the BOTTOM SIXTH of the photo\n"
                + "- ALSO EXCLUDE: trees, plants, vehicles, glass panes, AC units, electrical\n"
                + "  fixtures, stone cladding, exposed brick, ceramic tile, marble, wallpaper,\n"
                + "  wood paneling, furniture, decor, neighboring buildings.\n"
                + "- A mask number belongs to AT MOST ONE category. If two adjacent masks are\n"
                + "  obviously the same wall surface (same color, separated by a shadow line or a\n"
                + "  thin border), include BOTH in the same category.\n"
                + "- When in doubt about a wall-colored mask, INCLUDE it in main_wall rather than\n"
                + "  leave it out. Under-inclusion is the failure mode we're avoiding.\n\n"
                + "ALSO RETURN:\n"
                + "- paintable: false ONLY if no paintable wall is visible at all.\n"
                + "- wall_material: short description.\n"
                + "- notes: one sentence including roughly how many main_wall pieces you found and\n"
                + "  why you grouped them together.\n\n"
                + "Return ONLY this JSON. No markdown fences, no prose:\n\n"
                + "{\n"
                + "  \"paintable\": <true|false>,\n"
                + "  \"wall_material\": \"<string>\",\n"
                + "  \"main_wall\": [<mask numbers>],\n"
                + "  \"accent_wall\": [<mask numbers>],\n"
                + "  \"trim\": [<mask numbers>],\n"
                + "  \"notes\": \"<string>\"\n"
                + "}\n").formatted(typeHint, maskCount, mainHint, accentHint, trimHint);
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
