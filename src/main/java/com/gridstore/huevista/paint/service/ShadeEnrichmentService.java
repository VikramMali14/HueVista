package com.gridstore.huevista.paint.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadeEnrichmentService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.claude.api-key}")
    private String apiKey;

    @Value("${app.claude.enrichment-model:claude-haiku-4-5-20251001}")
    private String model;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final int BATCH_SIZE = 5;

    public record ShadeInput(
            String name,
            String hexCode,
            String shadeFamily,
            String colorTemperature,
            String tonality
    ) {}

    public record EnrichmentResult(
            List<String> styleTags,
            List<String> moodDescriptors,
            List<String> finishRecommendations,
            String aiDescription
    ) {}

    private static final EnrichmentResult EMPTY = new EnrichmentResult(List.of(), List.of(), List.of(), null);

    /**
     * Enriches a list of shades via Claude in batches of 5.
     * Returns one EnrichmentResult per input in the same order.
     */
    public List<EnrichmentResult> enrichBatch(List<ShadeInput> inputs) {
        List<EnrichmentResult> results = new ArrayList<>();

        for (int i = 0; i < inputs.size(); i += BATCH_SIZE) {
            List<ShadeInput> batch = inputs.subList(i, Math.min(i + BATCH_SIZE, inputs.size()));
            try {
                results.addAll(callClaude(batch));
                log.debug("Enriched shades {}-{}", i, i + batch.size() - 1);
            } catch (Exception e) {
                log.error("Enrichment failed for batch [{}-{}]: {}", i, i + batch.size() - 1, e.getMessage());
                batch.forEach(ignored -> results.add(EMPTY));
            }
        }

        return results;
    }

    private List<EnrichmentResult> callClaude(List<ShadeInput> batch) throws Exception {
        List<Map<String, Object>> shadesJson = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            ShadeInput s = batch.get(i);
            shadesJson.add(Map.of(
                    "index", i,
                    "name", s.name() != null ? s.name() : "",
                    "hex", s.hexCode() != null ? s.hexCode() : "",
                    "family", s.shadeFamily() != null ? s.shadeFamily() : "",
                    "temperature", s.colorTemperature() != null ? s.colorTemperature() : "",
                    "tonality", s.tonality() != null ? s.tonality() : ""
            ));
        }

        String prompt = """
                You are a professional interior design color consultant for the Indian market.
                Given paint shades, provide structured metadata for each.

                For each shade provide:
                - styleTags: 3-5 design style tags (e.g. "modern", "minimalist", "traditional", "rustic", "coastal", "Indian traditional")
                - moodDescriptors: 2-4 mood/emotion words (e.g. "calm", "energetic", "cozy", "refreshing")
                - finishRecommendations: 1-3 recommended finishes from: matte, eggshell, satin, semi-gloss, gloss
                - aiDescription: one natural sentence describing this shade and its ideal use in Indian homes

                Shades:
                """ + objectMapper.writeValueAsString(shadesJson) + """

                Respond with a JSON array ONLY — no markdown fences, no explanation:
                [{"index":0,"styleTags":[...],"moodDescriptors":[...],"finishRecommendations":[...],"aiDescription":"..."},...]
                """;

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        ResponseEntity<Map> response = restTemplate.exchange(
                CLAUDE_API_URL, HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                Map.class
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
        String rawJson = ((String) content.get(0).get("text")).trim();

        List<Map<String, Object>> parsed = objectMapper.readValue(rawJson, new TypeReference<>() {});
        parsed.sort(Comparator.comparingInt(m -> (Integer) m.get("index")));

        List<EnrichmentResult> results = new ArrayList<>();
        for (Map<String, Object> item : parsed) {
            results.add(new EnrichmentResult(
                    castList(item.get("styleTags")),
                    castList(item.get("moodDescriptors")),
                    castList(item.get("finishRecommendations")),
                    (String) item.get("aiDescription")
            ));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object obj) {
        if (obj instanceof List<?> list) return (List<String>) list;
        return List.of();
    }
}
