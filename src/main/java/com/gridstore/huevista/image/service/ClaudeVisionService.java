package com.gridstore.huevista.image.service;

import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ImageValidationException;
import com.gridstore.huevista.image.model.ImageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.tasks.UnsupportedFormatException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeVisionService {

    private final ClaudeService claude;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String model;

    private static final String PROMPT =
            "Classify this image for a paint color visualization app. " +
            "Is it an indoor room (living room, bedroom, kitchen, bathroom, hallway, office, etc.) " +
            "or an outdoor house/building exterior (facade, porch, wall, balcony, etc.)? " +
            "Answer with exactly one word only — no explanation: INDOOR, OUTDOOR, or INVALID. " +
            "INVALID means it is NOT a house or room image (e.g. selfie, food, nature, animal, document, car).";

    /**
     * Sends the image to Claude Vision and returns INDOOR, OUTDOOR, or null (= INVALID).
     * Image is resized to max 1024px before sending — reduces input tokens ~10x.
     */
    public ImageType classify(MultipartFile file) throws IOException {
        byte[] resizedBytes = resizeForClassification(file);
        String base64Data = Base64.getEncoder().encodeToString(resizedBytes);

        try {
            String answer = claude.askUser(model, 10, List.of(
                    ClaudeService.imageBase64Block("image/jpeg", base64Data), // always JPEG after resize
                    ClaudeService.textBlock(PROMPT)
            )).toUpperCase();
            log.debug("Claude Vision result: {}", answer);

            return switch (answer) {
                case "INDOOR"  -> ImageType.INDOOR;
                case "OUTDOOR" -> ImageType.OUTDOOR;
                default        -> null; // INVALID or unexpected token
            };
        } catch (Exception e) {
            log.error("Claude Vision API call failed: {}", e.getMessage());
            throw new ExternalServiceException("Image classification service is temporarily unavailable. Please try again.", e);
        }
    }

    // Resize to max 1024x1024 JPEG at 85% quality before sending to Claude.
    // Cuts input tokens ~10x and keeps classification accuracy identical.
    private byte[] resizeForClassification(MultipartFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Thumbnails.of(file.getInputStream())
                    .size(1024, 1024)
                    .keepAspectRatio(true)
                    .outputFormat("jpeg")
                    .outputQuality(0.85)
                    .toOutputStream(out);
        } catch (UnsupportedFormatException e) {
            // Content-Type header said JPEG/PNG/WebP, but the bytes are not a format
            // ImageIO can decode (e.g. HEIC from iOS, AVIF, or a corrupted file).
            throw new ImageValidationException(
                    "Unable to read the image. The file may be corrupted or in an unsupported format " +
                    "(e.g. HEIC from iPhone). Please upload a JPEG, PNG, or WebP image."
            );
        }
        return out.toByteArray();
    }
}
