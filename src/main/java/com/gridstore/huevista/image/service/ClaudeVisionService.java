package com.gridstore.huevista.image.service;

import com.gridstore.huevista.image.model.ImageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeVisionService {

    private final RestTemplate restTemplate;

    @Value("${app.claude.api-key}")
    private String apiKey;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    private static final String PROMPT =
            "Classify this image for a paint color visualization app. " +
            "Is it an indoor room (living room, bedroom, kitchen, bathroom, hallway, office, etc.) " +
            "or an outdoor house/building exterior (facade, porch, wall, balcony, etc.)? " +
            "Answer with exactly one word only — no explanation: INDOOR, OUTDOOR, or INVALID. " +
            "INVALID means it is NOT a house or room image (e.g. selfie, food, nature, animal, document, car).";

    /**
     * Sends the image to Claude Vision and returns INDOOR, OUTDOOR, or null (= INVALID).
     */
    public ImageType classify(MultipartFile file) throws IOException {
        String base64Data = Base64.getEncoder().encodeToString(file.getBytes());
        String mediaType = file.getContentType() != null ? file.getContentType() : "image/jpeg";

        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", mediaType,
                        "data", base64Data
                )
        );
        Map<String, Object> textBlock = Map.of("type", "text", "text", PROMPT);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 10,
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

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
            String answer = ((String) content.get(0).get("text")).trim().toUpperCase();
            log.debug("Claude Vision result: {}", answer);

            return switch (answer) {
                case "INDOOR"  -> ImageType.INDOOR;
                case "OUTDOOR" -> ImageType.OUTDOOR;
                default        -> null; // INVALID or unexpected token
            };
        } catch (Exception e) {
            log.error("Claude Vision API call failed: {}", e.getMessage());
            throw new RuntimeException("Image classification service is temporarily unavailable. Please try again.", e);
        }
    }
}
