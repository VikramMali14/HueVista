package com.gridstore.huevista.image.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ImageValidationException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.exception.StorageException;
import com.gridstore.huevista.image.dto.ImageResponse;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final ImageRepository imageRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ClaudeVisionService claudeVisionService;

    public ImageResponse upload(MultipartFile file, String userId) {
        // Step 1: basic file checks
        validateFile(file);

        // Step 2: AI classification — reject non-house images before touching storage
        ImageType imageType;
        try {
            imageType = claudeVisionService.classify(file);
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file", e);
        } catch (RuntimeException e) {
            // Claude API temporarily unavailable (e.g. 529 overloaded) — skip classification
            log.warn("Claude Vision unavailable, skipping classification: {}", e.getMessage());
            imageType = ImageType.UNKNOWN;
        }

        if (imageType == null) {
            throw new ImageValidationException(
                    "Please upload a photo of an indoor room or outdoor house/building exterior. " +
                    "Selfies, landscapes, food, and other non-house images are not accepted."
            );
        }

        // Step 3: persist to storage
        String storageKey;
        try {
            storageKey = storageService.store(file, userId);
        } catch (IOException e) {
            throw new StorageException("Failed to store image", e);
        }

        // Step 4: save metadata to DB
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        UploadedImage saved = imageRepository.save(
                UploadedImage.builder()
                        .user(user)
                        .originalFilename(file.getOriginalFilename())
                        .storageKey(storageKey)
                        .contentType(file.getContentType())
                        .fileSize(file.getSize())
                        .imageType(imageType)
                        .build()
        );

        log.info("Image uploaded: id={} type={} user={}", saved.getId(), imageType, userId);
        return toResponse(saved);
    }

    public ImageResponse getImage(String imageId, String userId) {
        UploadedImage image = imageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + imageId));
        return toResponse(image);
    }

    public List<ImageResponse> listImages(String userId) {
        return imageRepository.findByUserIdOrderByUploadedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImageValidationException("No file provided.");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ImageValidationException("Only JPEG, PNG, and WebP images are accepted.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ImageValidationException("File size must not exceed 10MB.");
        }
    }

    private ImageResponse toResponse(UploadedImage image) {
        return ImageResponse.builder()
                .imageId(image.getId())
                .imageUrl(storageService.getPublicUrl(image.getStorageKey()))
                .originalFilename(image.getOriginalFilename())
                .imageType(image.getImageType())
                .fileSize(image.getFileSize())
                .uploadedAt(image.getUploadedAt())
                .build();
    }
}
