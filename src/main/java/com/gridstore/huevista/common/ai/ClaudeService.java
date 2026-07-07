package com.gridstore.huevista.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ONE client for the Anthropic Messages API. Every feature that talks to
 * Claude — support agent, image classification, cleaning hints, shade
 * enrichment, colour recommendations — goes through here, so the endpoint,
 * auth headers, API version and response parsing live in exactly one place.
 *
 * Two levels:
 *  - {@link #complete}: fail-soft text conversations (returns Optional.empty()
 *    on a missing key or any error) — used by the support agent.
 *  - {@link #askUser}: a single user message of content blocks (text and/or
 *    images). Throws on transport/API errors so callers choose their own
 *    failure semantics (fail-hard classification vs fail-soft hints).
 */
@Slf4j
@Service
public class ClaudeService {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final RestTemplate restTemplate;

    @Value("${app.claude.api-key:}")
    private String apiKey;

    @Value("${app.claude.support-model:${app.claude.recommendation-model:claude-sonnet-4-6}}")
    private String supportModel;

    public ClaudeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** A single conversation turn. role is "user" or "assistant". */
    public record Turn(String role, String text) {}

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank() && !"dev-disabled".equals(apiKey);
    }

    // ── Content-block builders ─────────────────────────────────────────────

    public static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    public static Map<String, Object> imageUrlBlock(String url) {
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }

    public static Map<String, Object> imageBase64Block(String mediaType, String base64Data) {
        return Map.of("type", "image", "source",
                Map.of("type", "base64", "media_type", mediaType, "data", base64Data));
    }

    /** Strips the ```json fences Claude sometimes wraps a JSON answer in despite instructions. */
    public static String stripCodeFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        return s;
    }

    // ── Calls ──────────────────────────────────────────────────────────────

    /**
     * Sends the conversation to Claude (support model) and returns its text
     * reply, or empty if AI is unavailable (disabled key or any error).
     */
    public Optional<String> complete(String system, List<Turn> history, int maxTokens) {
        if (!isEnabled()) return Optional.empty();
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Turn t : history) {
                messages.add(Map.of("role", t.role(), "content", t.text()));
            }
            return Optional.of(send(supportModel, maxTokens, system, messages));
        } catch (Exception e) {
            log.warn("Claude support completion failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * One user message built from content blocks (see the static builders).
     * Returns the first text block of the reply, trimmed. Throws on any
     * transport/API problem — wrap per caller.
     */
    public String askUser(String model, int maxTokens, List<Map<String, Object>> contentBlocks) {
        return send(model, maxTokens, null,
                List.of(Map.of("role", "user", "content", contentBlocks)));
    }

    @SuppressWarnings("unchecked")
    private String send(String model, int maxTokens, String system, List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (system != null && !system.isBlank()) body.put("system", system);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", API_VERSION);

        Map<String, Object> resp = restTemplate.postForObject(
                ENDPOINT, new HttpEntity<>(body, headers), Map.class);
        if (resp == null) throw new IllegalStateException("Claude returned an empty response");
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalStateException("Claude response has no content");
        }
        Object text = content.get(0).get("text");
        if (!(text instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("Claude response has no text block");
        }
        return s.trim();
    }
}
