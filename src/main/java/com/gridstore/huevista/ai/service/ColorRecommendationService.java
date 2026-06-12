package com.gridstore.huevista.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.ai.dto.ColorCombo;
import com.gridstore.huevista.ai.dto.MatchedShade;
import com.gridstore.huevista.ai.dto.RecommendationResponse;
import com.gridstore.huevista.ai.util.DeltaEMatcher;
import com.gridstore.huevista.billing.service.BillingService;
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
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

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
    private final BillingService billingService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.claude.api-key}")
    private String apiKey;

    @Value("${app.claude.recommendation-model:claude-sonnet-4-6}")
    private String recommendationModel;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

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

        billingService.checkAndIncrementAiUsage(userId);

        String imageUrl = storageService.getPublicUrl(project.getImage().getStorageKey());
        String imageType = project.getImage().getImageType() != null
                ? project.getImage().getImageType().name()
                : "UNKNOWN";

        List<Map<String, Object>> rawCombos = callClaude(imageUrl);
        List<Shade> catalog = shadeRepository.findAll();

        List<ColorCombo> combos = new ArrayList<>();
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callClaude(String imageUrl) {
        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "url",
                        "url", imageUrl
                )
        );
        Map<String, Object> textBlock = Map.of("type", "text", "text", PROMPT);

        Map<String, Object> requestBody = Map.of(
                "model", recommendationModel,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(imageBlock, textBlock))
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    CLAUDE_API_URL, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    Map.class
            );

            List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
            String raw = ((String) content.get(0).get("text")).trim();

            // Strip markdown code fences if present
            if (raw.startsWith("```")) {
                raw = raw.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

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
