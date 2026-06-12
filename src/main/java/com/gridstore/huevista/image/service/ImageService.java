package com.gridstore.huevista.image.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
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
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final StorageService storageService;
    private final ClaudeVisionService claudeVisionService;

    public ImageResponse upload(MultipartFile file, String userId) {
        // Step 1: basic file checks (size, declared type, magic bytes)
        String contentType = validateFile(file);

        // Step 2: AI classification — reject non-house images before touching storage
        ImageType imageType;
        try {
            imageType = claudeVisionService.classify(file);
        } catch (ImageValidationException e) {
            // The bytes aren't a decodable image (corrupt / HEIC / AVIF) — a client error.
            // Surface it as-is (422) instead of letting it fall into the "Claude down" branch
            // below, which would mislabel it as UNKNOWN and store an unusable upload.
            throw e;
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
                        .contentType(contentType)
                        .fileSize(file.getSize())
                        .imageType(imageType)
                        .build()
        );

        log.info("Image uploaded: id={} type={} user={}", saved.getId(), imageType, userId);
        return toResponse(saved);
    }

    /**
     * Upload for an anonymous GUEST, owned by their access code (no user). We skip
     * Claude Vision classification here on purpose: it costs an AI call per upload,
     * which is an abuse vector for an unauthenticated endpoint. Guests draw their
     * own regions by hand anyway, so the image type isn't needed — stored as UNKNOWN.
     */
    public ImageResponse uploadForGuest(MultipartFile file, String accessCodeId) {
        String contentType = validateFile(file);

        CustomerAccessCode accessCode = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found: " + accessCodeId));

        String storageKey;
        try {
            storageKey = storageService.store(file, accessCodeId);
        } catch (IOException e) {
            throw new StorageException("Failed to store image", e);
        }

        UploadedImage saved = imageRepository.save(
                UploadedImage.builder()
                        .accessCode(accessCode)
                        .originalFilename(file.getOriginalFilename())
                        .storageKey(storageKey)
                        .contentType(contentType)
                        .fileSize(file.getSize())
                        .imageType(ImageType.UNKNOWN)
                        .build()
        );

        log.info("Guest image uploaded: id={} accessCode={}", saved.getId(), accessCodeId);
        return toResponse(saved);
    }

    public ImageResponse getImage(String imageId, String userId) {
        UploadedImage image = imageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + imageId));
        return toResponse(image);
    }

    public List<ImageResponse> listImages(String userId, int page, int size) {
        // Capped: bounds memory and response size; newest uploads win.
        // Clamp instead of rejecting: page >= 0, 1 <= size <= 200.
        return imageRepository.findByUserIdOrderByUploadedAtDesc(
                        userId, org.springframework.data.domain.PageRequest.of(
                                Math.max(0, page), Math.min(Math.max(1, size), 200)))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Validates the upload and returns the content type detected from the file's
     * magic bytes. The client-declared Content-Type header is checked too, but it is
     * attacker-controlled — the sniffed type is what gets persisted and trusted.
     */
    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImageValidationException("No file provided.");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ImageValidationException("Only JPEG, PNG, and WebP images are accepted.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ImageValidationException("File size must not exceed 10MB.");
        }
        String detected;
        try {
            detected = sniffImageType(file);
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file", e);
        }
        if (detected == null) {
            throw new ImageValidationException("File content is not a valid JPEG, PNG, or WebP image.");
        }
        return detected;
    }

    /** Returns the MIME type implied by the file's magic bytes, or null if not an allowed image. */
    private static String sniffImageType(MultipartFile file) throws IOException {
        byte[] header = new byte[12];
        int read;
        try (var in = file.getInputStream()) {
            read = in.readNBytes(header, 0, header.length);
        }
        if (read >= 3
                && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (read >= 8
                && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return "image/png";
        }
        if (read >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "image/webp";
        }
        return null;
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
