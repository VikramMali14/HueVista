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
    private final StorageService storageService;
    private final ClaudeService claude;
    private final ObjectMapper objectMapper;

    @Value("${app.claude.recommendation-model:claude-sonnet-4-6}")
    private String recommendationModel;

    private static final String PROMPT = """
            You are an expert Indian interior and exterior color consultant with deep knowledge of Asian Paints, Berger, and Nerolac shade ranges.

            Analyze this room/building photo and suggest exactly 3 paint color combination palettes that would look beautiful, are culturally suitable for the Indian market, and work well with the existing lighting and furnishings visible.

            For each palette provide:
            - name: a creative palette name (2-4 words, evocative)
            - rationale: one sentence explaining why this palette works for this specific space
            - primaryHex: hex color for the primary/main walls (most surface area)
            - accentHex: hex color for an accent wall, feature wall, or secondary surface
            - trimHex: hex color for window frames, door trims, skirting, or ceiling

            Return ONLY a valid JSON array with exactly 3 objects. No markdown, no explanation, just the raw JSON array.
            Example format:
            [{"name":"Monsoon Calm","rationale":"Cool blues create serenity in this sunlit living room.","primaryHex":"#B8D4E8","accentHex":"#7BA7C4","trimHex":"#F5F5F5"}]
            """;

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

        List<ColorCombo> combos = new ArrayList<>();
        String imageUrl = storageService.getPublicUrl(project.getImage().getStorageKey());
        List<Map<String, Object>> rawCombos = callClaude(imageUrl);
        List<Shade> catalog = shadeRepository.findAll();

        for (Map<String, Object> raw : rawCombos) {
            String primaryHex = normalize((String) raw.get("primaryHex"));
            String accentHex = normalize((String) raw.get("accentHex"));
            String trimHex = normalize((String) raw.get("trimHex"));

            Shade primaryShade = DeltaEMatcher.findNearest(primaryHex, catalog);
            Shade accentShade = DeltaEMatcher.findNearest(accentHex, catalog);
            Shade trimShade = DeltaEMatcher.findNearest(trimHex, catalog);

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

    private List<Map<String, Object>> callClaude(String imageUrl) {
        try {
            String raw = ClaudeService.stripCodeFences(
                    claude.askUser(recommendationModel, 1024, List.of(
                            ClaudeService.imageUrlBlock(imageUrl),
                            ClaudeService.textBlock(PROMPT)
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
}
