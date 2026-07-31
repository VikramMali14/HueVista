package com.gridstore.huevista.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.ai.dto.ColorCombo;
import com.gridstore.huevista.ai.dto.MatchedShade;
import com.gridstore.huevista.ai.dto.RecommendationResponse;
import com.gridstore.huevista.ai.util.DeltaEMatcher;
import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ColorRecommendationService {

    private final ProjectRepository projectRepository;
    private final ShadeRepository shadeRepository;
    private final com.gridstore.huevista.project.repository.RegionRepository regionRepository;
    private final StorageService storageService;
    private final ClaudeService claude;
    private final ObjectMapper objectMapper;

    @Value("${app.claude.recommendation-model:claude-sonnet-4-6}")
    private String recommendationModel;

    /**
     * The prompt is built per project rather than fixed, because how many colours a
     * palette should have is a property of the ROOM, not of the model: a photo masked into
     * one wall cannot use three colours, and offering three there produced two swatches
     * the shop had nowhere to put. {@code %d} is the number of masked regions, clamped to
     * {@link #MAX_COLOURS}.
     */
    private static String promptFor(int colours) {
        String slots = switch (colours) {
            case 1 -> """
                    - primaryHex: hex color for the single paintable surface in this photo
                    Return ONLY primaryHex for each palette. Do NOT include accentHex or trimHex.""";
            case 2 -> """
                    - primaryHex: hex color for the primary/main walls (most surface area)
                    - accentHex: hex color for the second surface — an accent wall or feature wall
                    Return ONLY primaryHex and accentHex for each palette. Do NOT include trimHex.""";
            default -> """
                    - primaryHex: hex color for the primary/main walls (most surface area)
                    - accentHex: hex color for an accent wall, feature wall, or secondary surface
                    - trimHex: hex color for window frames, door trims, skirting, or ceiling""";
        };
        String example = switch (colours) {
            case 1 -> """
                    [{"name":"Monsoon Calm","rationale":"Cool blues create serenity in this sunlit living room.","primaryHex":"#B8D4E8"}]""";
            case 2 -> """
                    [{"name":"Monsoon Calm","rationale":"Cool blues create serenity in this sunlit living room.","primaryHex":"#B8D4E8","accentHex":"#7BA7C4"}]""";
            default -> """
                    [{"name":"Monsoon Calm","rationale":"Cool blues create serenity in this sunlit living room.","primaryHex":"#B8D4E8","accentHex":"#7BA7C4","trimHex":"#F5F5F5"}]""";
        };
        return """
            You are an expert Indian interior and exterior color consultant with deep knowledge of Asian Paints, Berger, and Nerolac shade ranges.

            Analyze this room/building photo and suggest exactly 3 paint color combination palettes that would look beautiful, are culturally suitable for the Indian market, and work well with the existing lighting and furnishings visible.

            This photo has %d paintable surface%s marked, so each palette must use EXACTLY %d color%s — no more.

            For each palette provide:
            - name: a creative palette name (2-4 words, evocative)
            - rationale: one sentence explaining why this palette works for this specific space
            %s

            Return ONLY a valid JSON array with exactly 3 objects. No markdown, no explanation, just the raw JSON array.
            Example format:
            %s
            """.formatted(colours, colours == 1 ? "" : "s", colours, colours == 1 ? "" : "s",
                          slots, example);
    }

    /** Most colours a palette can carry — main, accent and trim, the three roles the
     *  studio can actually paint. */
    private static final int MAX_COLOURS = 3;

    @Transactional(readOnly = true)
    public RecommendationResponse getRecommendations(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        String imageType = project.getImage().getImageType() != null
                ? project.getImage().getImageType().name()
                : "UNKNOWN";

        // Nothing is charged here. Palette suggestions used to spend an "AI generation" —
        // the same credit an image cost — which under the single-project model would mean
        // charging a whole project (₹45-99 of allowance) to suggest three colour combos
        // for a project that has already been paid for. A project covers everything done
        // inside it, and this is inside it.
        //
        // The gate is the project's own access window rather than a subscription: a shop
        // between plans that bought this project outright holds no subscription at all, and
        // asking for one here would lock them out of something they have paid for. A window
        // that never opened (null on both fields) is a plan-covered project and passes.
        if (project.hasAccessWindow() && !project.isAccessWindowOpen()) {
            throw new com.gridstore.huevista.common.exception.QuotaExceededException(
                    "This project's access window has closed. Reopen it to keep working on it.");
        }

        // As many colours as this photo has paintable surfaces, capped at the three roles
        // the studio can paint. Asking for three on a one-wall room produced two swatches
        // the shop had nowhere to put; asking again after a wall is added now returns the
        // extra colour, because the count is read fresh on every ask.
        int colours = Math.max(1, Math.min(MAX_COLOURS, regionRepository.countByProjectId(projectId)));

        List<ColorCombo> combos = new ArrayList<>();
        String imageUrl = storageService.getPublicUrl(project.getImage().getStorageKey());
        List<Map<String, Object>> rawCombos = callClaude(imageUrl, colours);
        List<Shade> catalog = shadeRepository.findAll();

        for (Map<String, Object> raw : rawCombos) {
            // Slots beyond the count stay null even if the model volunteers them, so the
            // studio never shows a colour there is no surface for.
            String primaryHex = normalize((String) raw.get("primaryHex"));
            String accentHex = colours >= 2 ? normalize((String) raw.get("accentHex")) : null;
            String trimHex = colours >= 3 ? normalize((String) raw.get("trimHex")) : null;

            // A null hex is a slot this room has no surface for — never matched, so the
            // shade stays null too and the whole role drops out of the response.
            Shade primaryShade = nearest(primaryHex, catalog);
            Shade accentShade = nearest(accentHex, catalog);
            Shade trimShade = nearest(trimHex, catalog);

            combos.add(ColorCombo.builder()
                    .name((String) raw.get("name"))
                    .rationale((String) raw.get("rationale"))
                    .primaryHex(primaryHex)
                    .primaryShade(primaryShade != null
                            ? MatchedShade.from(primaryShade, DeltaEMatcher.computeDeltaE(primaryHex, primaryShade.getHexCode()))
                            : null)
                    .accentHex(accentHex)
                    .accentShade(accentShade != null
                            ? MatchedShade.from(accentShade, DeltaEMatcher.computeDeltaE(accentHex, accentShade.getHexCode()))
                            : null)
                    .trimHex(trimHex)
                    .trimShade(trimShade != null
                            ? MatchedShade.from(trimShade, DeltaEMatcher.computeDeltaE(trimHex, trimShade.getHexCode()))
                            : null)
                    .build());
        }

        log.info("Color recommendations generated: project={} combos={}", projectId, combos.size());
        return RecommendationResponse.builder()
                .projectId(projectId)
                .imageType(imageType)
                .combinations(combos)
                .build();
    }

    private List<Map<String, Object>> callClaude(String imageUrl, int colours) {
        try {
            String raw = ClaudeService.stripCodeFences(
                    claude.askUser(recommendationModel, 1024, List.of(
                            ClaudeService.imageUrlBlock(imageUrl),
                            ClaudeService.textBlock(promptFor(colours))
                    )));
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Claude recommendation API call failed: {}", e.getMessage());
            throw new ExternalServiceException("Color recommendation service is temporarily unavailable.", e);
        }
    }

    private String normalize(String hex) {
        if (hex == null) return "#808080";
        return hex.startsWith("#") ? hex : "#" + hex;
    }

    private Shade nearest(String hex, List<Shade> catalog) {
        return hex == null ? null : DeltaEMatcher.findNearest(hex, catalog);
    }
}
