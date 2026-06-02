package com.gridstore.huevista.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Small shared text-completion client for the Anthropic Messages API. Used by the
 * support agent (and reusable elsewhere). Fails soft: returns Optional.empty()
 * when the key is missing/disabled (dev) or the call errors, so callers degrade
 * gracefully (e.g. hand off to a human) instead of throwing.
 */
@Slf4j
@Service
public class ClaudeService {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";

    private final RestTemplate restTemplate;

    @Value("${app.claude.api-key:}")
    private String apiKey;

    @Value("${app.claude.support-model:${app.claude.recommendation-model:claude-sonnet-4-6}}")
    private String model;

    public ClaudeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** A single conversation turn. role is "user" or "assistant". */
    public record Turn(String role, String text) {}

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank() && !"dev-disabled".equals(apiKey);
    }

    /**
     * Sends the conversation to Claude and returns its text reply, or empty if AI
     * is unavailable (disabled key or any error).
     */
    @SuppressWarnings("unchecked")
    public Optional<String> complete(String system, List<Turn> history, int maxTokens) {
        if (!isEnabled()) return Optional.empty();
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            for (Turn t : history) {
                messages.add(Map.of("role", t.role(), "content", t.text()));
            }
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", system,
                    "messages", messages
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> resp = restTemplate.postForObject(
                    ENDPOINT, new HttpEntity<>(body, headers), Map.class);
            if (resp == null) return Optional.empty();
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            if (content == null || content.isEmpty()) return Optional.empty();
            Object text = content.get(0).get("text");
            return text == null ? Optional.empty() : Optional.of(text.toString().trim());
        } catch (Exception e) {
            log.warn("Claude support completion failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
