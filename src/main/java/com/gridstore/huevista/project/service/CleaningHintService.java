package com.gridstore.huevista.project.service;

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
 * Optional "hybrid" step before cleaning: a cheap Claude vision call that looks at THIS
 * specific photo and returns a short, image-grounded addendum for the cleaning prompt —
 * the exact clutter to remove and the exact elements to preserve. Appended to the
 * scene base prompt so the image-editing model gets precise instructions instead of a
 * generic list.
 *
 * Fails soft: returns empty on any error or when not configured, so the cleaner simply
 * falls back to its base interior/exterior prompt. Uses the cheap Haiku model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleaningHintService {

    private final RestTemplate restTemplate;

    @Value("${app.claude.api-key:}")
    private String apiKey;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${replicate.image-cleaner.hybrid-hints-enabled:true}")
    private boolean enabled;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    /**
     * @return a short "REMOVE: … / PRESERVE: …" addendum grounded in this image, or empty.
     */
    public Optional<String> describeCleanup(String imageUrl, ImageType scene) {
        if (!enabled || apiKey == null || apiKey.isBlank() || "dev-disabled".equals(apiKey)) {
            return Optional.empty();
        }
        boolean exterior = scene != ImageType.INDOOR;
        String sceneWord = exterior ? "building exterior" : "interior room";
        // Exteriors can be photographed mid-construction (half-plastered walls), so we also
        // ask for a FINISH list there; interiors only get REMOVE/PRESERVE.
        String finishList = exterior
                ? "FINISH: any wall that is clearly unfinished or only partly plastered — bare "
                + "cement, raw brick/blockwork, or patchy half-applied plaster — that should be "
                + "completed into one smooth paintable plastered wall. Omit this list if every "
                + "wall is already finished.\n"
                : "";
        String headings = exterior ? "'REMOVE:', 'PRESERVE:' and 'FINISH:'" : "'REMOVE:' and 'PRESERVE:'";
        String instruction =
                "You are preparing edit instructions to CLEAN this " + sceneWord + " photo for a paint "
              + "visualizer (remove clutter, keep the structure identical). Look at THIS image and output "
              + "short bulleted lists, nothing else:\n"
              + "REMOVE: the specific clutter, temporary objects, wires, or damage actually visible here.\n"
              + "PRESERVE: the specific architectural features actually visible here that must stay "
              + "identical (windows, doors, frames, fixtures, cabinetry, railings, etc.).\n"
              + finishList
              + "Be concrete and brief, one item per line. No preamble and no headings other than "
              + headings + ".";
        try {
            Map<String, Object> imageBlock = Map.of(
                    "type", "image",
                    "source", Map.of("type", "url", "url", imageUrl));
            Map<String, Object> textBlock = Map.of("type", "text", "text", instruction);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 400,
                    "messages", List.of(Map.of("role", "user",
                            "content", List.of(imageBlock, textBlock))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> response = restTemplate.exchange(
                    CLAUDE_API_URL, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content =
                    response.getBody() == null ? null : (List<Map<String, Object>>) response.getBody().get("content");
            if (content == null || content.isEmpty()) return Optional.empty();
            Object text = content.get(0).get("text");
            if (!(text instanceof String s) || s.isBlank()) return Optional.empty();
            log.info("CleaningHintService produced image-specific hints ({} chars)", s.length());
            return Optional.of(s.trim());
        } catch (Exception e) {
            log.warn("CleaningHintService failed, using base prompt only: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
