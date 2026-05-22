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
 * Pre-analyzes the uploaded photograph with Claude Haiku Vision so we can
 * feed Grounded SAM a smarter, image-specific negative prompt instead of the
 * same static list every time. Branches on INDOOR vs OUTDOOR — the
 * vocabulary that matters is completely different across the two
 * (furniture/decor vs sky/trees/vehicles).
 *
 * Returns Optional.empty() on any failure (API down, malformed response,
 * key missing) so the caller can fall back to the hardcoded prompt rather
 * than fail the whole segmentation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WallSceneAnalyzer {

    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;

    @Value("${app.claude.api-key:}")
    private String apiKey;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${app.segmentation.scene-analysis.enabled:true}")
    private boolean enabled;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    /** Cap on items kept from Claude's exclude list — keeps the Grounded SAM prompt tractable. */
    private static final int MAX_EXCLUDES = 30;

    /** Drop suspicious / overly long entries — Claude occasionally returns full sentences. */
    private static final int MAX_EXCLUDE_LENGTH = 60;

    public Optional<WallSceneAnalysis> analyze(String imageUrl, ImageType imageType) {
        if (!enabled) {
            log.debug("Scene analysis disabled by config");
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Claude API key not set — scene analysis disabled");
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
                    "max_tokens", 800,
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

            List<String> excludes = new ArrayList<>();
            JsonNode arr = root.path("exclude_objects");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String s = n.asText("").trim().toLowerCase();
                    if (s.isBlank() || s.length() > MAX_EXCLUDE_LENGTH) continue;
                    excludes.add(s);
                    if (excludes.size() >= MAX_EXCLUDES) break;
                }
            }

            log.info("Scene analysis [{}]: paintable={} material='{}' excludes={}",
                    imageType, paintable, material, excludes.size());
            return Optional.of(new WallSceneAnalysis(paintable, material, excludes, notes));

        } catch (Exception e) {
            log.warn("Scene analysis failed, falling back to default prompt: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(ImageType imageType) {
        boolean outdoor = imageType == ImageType.OUTDOOR;
        String typeHint = outdoor ? "OUTDOOR (building facade)" : "INDOOR (room)";
        String focusHint = outdoor
                ? "Focus on: sky, clouds, trees, bushes, plants, lawn, road, sidewalk, driveway, "
                + "parked cars, vehicles, fences, gates, garage doors, balconies, roof, chimney, "
                + "drainpipes, gutters, AC units, electricity meters, signage, mailboxes, lamp posts, "
                + "other buildings in the background."
                : "Focus on: furniture (sofa, bed, chair, table, dresser, bookshelf), decor (paintings, "
                + "mirrors, picture frames, clocks, wall art), wall-mounted electronics (television, "
                + "speakers, intercom), light fixtures (chandelier, pendant light, sconce, ceiling fan, "
                + "lamp), electrical (light switches, electrical outlets, thermostat), HVAC (ceiling "
                + "vents, AC unit, smoke detector), curtains, blinds, indoor plants.";

        return ("You are analyzing an %s photograph for a paint visualization app.\n"
                + "Return ONLY valid JSON in this exact schema — no markdown fences, no preamble, "
                + "no explanation outside the JSON object:\n\n"
                + "{\n"
                + "  \"paintable\": <true or false>,\n"
                + "  \"wall_material\": \"<short description of visible wall surface>\",\n"
                + "  \"exclude_objects\": [<list of every non-paintable object visible>],\n"
                + "  \"notes\": \"<one short sentence>\"\n"
                + "}\n\n"
                + "Rules:\n"
                + "- \"paintable\" = true ONLY if visible walls are painted plaster, drywall, or "
                + "concrete that CAN be repainted. Set FALSE for: exposed brick, raw stone, ceramic "
                + "tile, marble, granite, wallpaper, wood paneling, vinyl siding, metal cladding, glass.\n"
                + "- \"exclude_objects\" = every visible object that is NOT a paintable wall surface. "
                + "Use specific lowercase noun phrases (e.g. \"wooden front door\", \"ceiling fan with "
                + "three blades\", \"wall-mounted 55-inch television\"). Include 8-25 items. "
                + "Do NOT include the walls themselves.\n"
                + "- %s\n"
                + "- If walls are not paintable, still list excluded objects so the user can see what "
                + "you identified.\n").formatted(typeHint, focusHint);
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
        // Trim anything before the first '{' and after the last '}', in case
        // Claude wrapped the JSON in prose despite the prompt.
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
