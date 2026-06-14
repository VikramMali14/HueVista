package com.gridstore.huevista.project.service;

import com.gridstore.huevista.common.exception.ExternalServiceException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Segments room and facade images for paint visualization.
 *
 * <h3>Auto flow (segmentAsync)</h3>
 * <ol>
 *   <li>(Optional) Send the photo to {@link ImageCleanerService} —
 *       Nano Banana Pro removes wires/bushes/clutter AND refreshes the
 *       painted surfaces in their existing color, so the canvas the masks
 *       are aligned to looks like a fresh repaint. Opt-in via
 *       REPLICATE_IMAGE_CLEANER_ENABLED.</li>
 *   <li>One Nano Banana call ({@link ReplicateNanoBananaSegmenter})
 *       returns a single color-coded mask: WHITE = main paintable wall,
 *       BLUE = accent / highlighter wall, GREEN = trim & frames, BLACK =
 *       everything else (sky, ground, stone, doors, windows, fixtures).</li>
 *   <li>{@link MaskProcessor#splitColorCodedMask} splits the colored mask
 *       into per-category binary masks server-side.</li>
 *   <li>Each non-empty category is morph-cleaned, uploaded to S3 and
 *       persisted as a {@link Region} row.</li>
 * </ol>
 *
 * <h3>Manual flow (segmentPointAndSave)</h3>
 * The user clicks a point on the photo in the frontend. We call SAM 2 on
 * Replicate with that single point as a positive prompt; SAM returns the
 * mask of the surface at the click. Saved as a MANUAL Region. This is
 * the safety-net for cases the auto path misses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentationService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final StorageService storageService;
    private final RestTemplate restTemplate;
    private final ReplicateNanoBananaSegmenter replicateNanoBanana;
    private final ImageCleanerService imageCleaner;
    private final ImageRepository imageRepository;
    private final com.gridstore.huevista.billing.service.BillingService billingService;
    private final com.gridstore.huevista.account.repository.CustomerAccessCodeRepository accessCodeRepository;
    private final com.gridstore.huevista.account.repository.OrgMembershipRepository orgMembershipRepository;

    /** Optional, mirrors ProjectService: present when the Redis-backed queue is in play. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    /**
     * SAM 2 model version on Replicate (for click-to-segment). When blank,
     * we call the official endpoint /models/meta/sam-2/predictions which
     * always uses the latest published version.
     */
    @Value("${replicate.sam2.model-version:}")
    private String sam2ModelVersion;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 30; // 60s worst case per request

    /**
     * Click-to-segment is synchronous: the request thread blocks while SAM 2 runs
     * (up to ~60s). Without a cap, a burst of clicks can hold every Tomcat worker
     * hostage and wedge the whole API. Beyond this many concurrent segmentations
     * we fail fast with 503 instead of queueing more blocked threads.
     */
    private static final int MAX_CONCURRENT_POINT_SEGMENTATIONS = 8;
    private final java.util.concurrent.Semaphore pointSegmentationSlots =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_POINT_SEGMENTATIONS);

    // ========================================================================
    // AUTO PATH
    // ========================================================================

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation: project={}", projectId);

            if (replicateApiToken == null || replicateApiToken.isBlank()) {
                markFailed(projectId, "REPLICATE_API_TOKEN not configured");
                return;
            }

            // Storage scope: a normal project is owned by a user; a guest project
            // (no user) is owned by its access code. Either way this string is only
            // used as the storage prefix for the cleaned image and mask uploads.
            // A non-null accessCodeId here also marks this as a GUEST run, whose AI
            // cost is billed to the issuing shop — but only once it succeeds (below).
            String ownerUserId = projectRepository.findUserIdById(projectId).orElse(null);
            String guestAccessCodeId = ownerUserId == null
                    ? projectRepository.findAccessCodeIdById(projectId).orElse(null) : null;
            String userId = ownerUserId != null ? ownerUserId : guestAccessCodeId;
            if (userId == null) {
                markFailed(projectId, "Project owner not found");
                return;
            }

            // Wipe stale auto regions; MANUAL click-segments are preserved.
            regionRepository.deleteAutoRegionsByProjectId(projectId);

            // Load + cache dimensions on the upload entity so click-segment
            // later doesn't have to.
            UploadedImage uploadedImage = loadAndEnsureDimensions(projectId);

            // Step 1: Image cleaner (optional, opt-in). Sends the photo to
            // Nano Banana Pro asking for clutter removed AND painted
            // surfaces refreshed in the SAME color. When it succeeds the
            // cleaned image becomes the canvas the masks are aligned to;
            // otherwise we mask the original directly.
            String maskImageUrl = imageUrl;
            try {
                Optional<byte[]> cleanedOpt = imageCleaner.cleanImage(imageUrl, uploadedImage.getImageType());
                if (cleanedOpt.isPresent()) {
                    byte[] cleanedBytes = cleanedOpt.get();
                    String cleanedKey = storageService.store(
                            cleanedBytes, userId, "cleaned.jpg", "image/jpeg");
                    persistCleanedImageKey(projectId, cleanedKey);
                    maskImageUrl = storageService.getPublicUrl(cleanedKey);
                    log.info("ImageCleaner produced cleaned image for project {}, storageKey={}",
                            projectId, cleanedKey);
                }
            } catch (Exception e) {
                log.warn("ImageCleaner step failed for project {}, using original: {}",
                        projectId, e.getMessage());
            }

            // Step 2: Nano Banana color-coded mask via Replicate.
            if (tryReplicateNanoBananaSegmentation(projectId, userId, maskImageUrl)) {
                markSegmented(projectId);
                // Guest runs are billed to the issuing shop, but only now that walls
                // were actually produced — a failed run never costs the shop a credit.
                billGuestSegmentationIfNeeded(guestAccessCodeId);
                log.info("Segmentation complete: project={}", projectId);
                return;
            }

            // Nothing worked — surface a clear failure so the UI can prompt
            // the user to click-segment manually.
            markFailed(projectId,
                    "Auto-segmentation failed — Nano Banana didn't produce usable masks. " +
                    "Use click-to-segment to mark walls manually.");

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, "Segmentation failed: " + e.getMessage());
        } finally {
            // The run reached a terminal outcome (SEGMENTED or FAILED), so the queue
            // entry must not be retried. No-op when the job didn't come from the queue.
            if (segmentationJobQueue != null) {
                try {
                    segmentationJobQueue.acknowledge(projectId, imageUrl);
                } catch (Exception ackError) {
                    log.warn("Could not acknowledge segmentation job for project {}: {}",
                            projectId, ackError.getMessage());
                }
            }
        }
    }

    /**
     * One Nano Banana call returns a single color-coded image; we split
     * it into per-category binary masks and persist each non-empty one
     * as a Region row.
     *
     * Pixel size thresholds (5000 px for walls, 2000 px for trim) filter
     * categories Nano Banana barely produced — usually a sign the model
     * couldn't find that surface in the photo (e.g. no distinct accent
     * wall). We skip saving them rather than persisting a tiny noise mask.
     */
    private boolean tryReplicateNanoBananaSegmentation(String projectId, String userId,
                                                       String imageUrl) {
        try {
            if (!replicateNanoBanana.isConfigured()) {
                log.warn("Nano Banana (Replicate) not configured — set " +
                        "REPLICATE_NANO_BANANA_ENABLED=true");
                return false;
            }

            Optional<byte[]> colorRaw = replicateNanoBanana.generateColorCodedMask(imageUrl);
            if (colorRaw.isEmpty()) {
                log.info("Nano Banana returned no color-coded mask for project {}", projectId);
                return false;
            }

            Map<String, byte[]> parts;
            try {
                parts = MaskProcessor.splitColorCodedMask(colorRaw.get(), 2000);
            } catch (Exception e) {
                log.warn("splitColorCodedMask failed for project {}: {}", projectId, e.getMessage());
                return false;
            }
            log.info("Nano Banana split [project={}]: {}", projectId, parts.keySet());

            int saved = 0;
            int displayOrder = 0;
            boolean mainSaved = false;

            byte[] mainBytes = parts.get("main");
            if (mainBytes != null && safeForegroundCount(mainBytes) >= 5000) {
                saveCategoryRegion(projectId, userId, safeClean(mainBytes),
                        "Main Wall", RegionCategory.MAIN_WALL, displayOrder++);
                saved++;
                mainSaved = true;
            }
            byte[] accentBytes = parts.get("accent");
            if (accentBytes != null && safeForegroundCount(accentBytes) >= 5000) {
                saveCategoryRegion(projectId, userId, safeClean(accentBytes),
                        "Accent Wall", RegionCategory.ACCENT_WALL, displayOrder++);
                saved++;
            }
            byte[] trimBytes = parts.get("trim");
            if (trimBytes != null && safeForegroundCount(trimBytes) >= 2000) {
                saveCategoryRegion(projectId, userId, safeClean(trimBytes),
                        "Trim & Frames", RegionCategory.TRIM, displayOrder++);
                saved++;
            }

            if (!mainSaved) {
                log.info("Nano Banana didn't produce a usable main wall for project {}", projectId);
                return false;
            }
            log.info("Nano Banana saved {} regions for project {}", saved, projectId);
            return true;
        } catch (Exception e) {
            log.warn("Nano Banana path failed for project {}: {}", projectId, e.getMessage(), e);
            return false;
        }
    }

    private void saveCategoryRegion(String projectId, String userId, byte[] maskBytes,
                                    String label, RegionCategory category, int displayOrder)
            throws java.io.IOException {
        String key = storageService.store(
                maskBytes, userId, category.name().toLowerCase() + ".png", "image/png");
        regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(label)
                .category(category)
                .maskUrl(storageService.getPublicUrl(key))
                .maskData(storageService.getPublicUrl(key))
                .displayOrder(displayOrder)
                .build());
        log.info("Saved {} region for project {}: {}", category, projectId, key);
    }

    /** Morph-cleans a mask, falling back to the input if cleaning fails. */
    private byte[] safeClean(byte[] mask) {
        try {
            return MaskProcessor.morphClean(mask);
        } catch (Exception e) {
            return mask;
        }
    }

    /** Counts foreground pixels, returning 0 if the mask can't be decoded. */
    private int safeForegroundCount(byte[] mask) {
        try {
            return MaskProcessor.countForeground(mask);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Charges one AI generation to the shop that issued a guest's access code, after a
     * guest run has actually produced walls. No-op for normal (user-owned) runs — pass
     * null. Best-effort: a missing code/org/owner just means no charge (the guest still
     * got their result), so billing problems never fail an otherwise-successful run.
     */
    private void billGuestSegmentationIfNeeded(String guestAccessCodeId) {
        if (guestAccessCodeId == null) return;
        try {
            String orgId = accessCodeRepository.findOrganizationIdById(guestAccessCodeId).orElse(null);
            if (orgId == null) return;
            orgMembershipRepository
                    .findUserIdsByOrganizationIdAndRole(orgId, com.gridstore.huevista.account.model.OrgMemberRole.OWNER)
                    .stream().findFirst()
                    .ifPresent(billingService::incrementAiUsage);
        } catch (Exception e) {
            log.warn("Guest segmentation succeeded but billing the shop failed (code={}): {}",
                    guestAccessCodeId, e.getMessage());
        }
    }

    // ========================================================================
    // MANUAL PATH (click-to-segment)
    // ========================================================================

    /**
     * Synchronously segments a single user-clicked point with SAM 2 and
     * persists the resulting mask as a MANUAL region. Coordinates are
     * normalized 0–1 in the frontend; we scale by the image's real pixel
     * dimensions (cached on UploadedImage) before sending to SAM 2.
     */
    public Region segmentPointAndSave(String projectId, String imageUrl,
                                      int imageWidth, int imageHeight,
                                      double x, double y, String label)
            throws InterruptedException {
        if (!pointSegmentationSlots.tryAcquire()) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "Too many segmentations are running right now. Please try again in a moment.");
        }
        try {
            return doSegmentPointAndSave(projectId, imageUrl, imageWidth, imageHeight, x, y, label);
        } finally {
            pointSegmentationSlots.release();
        }
    }

    private Region doSegmentPointAndSave(String projectId, String imageUrl,
                                         int imageWidth, int imageHeight,
                                         double x, double y, String label)
            throws InterruptedException {
        log.info("Point segmentation: project={} x={} y={} size={}x{} label={}",
                projectId, x, y, imageWidth, imageHeight, label);

        double pixelX = x * imageWidth;
        double pixelY = y * imageHeight;
        List<List<Double>> inputPoints = List.of(List.of(pixelX, pixelY));
        List<Integer> inputLabels = List.of(1);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "input_points", inputPoints,
                "input_labels", inputLabels
        );

        String predictionId = startSam2Prediction(input);
        if (predictionId == null) {
            throw new ExternalServiceException("Failed to create Replicate prediction for point segmentation");
        }
        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new ExternalServiceException("Point segmentation timed out or failed");
        }
        String maskUrl = extractFirstMaskUrl(result.get("output"));
        if (maskUrl == null) {
            throw new ExternalServiceException("No mask URL in SAM 2 point segmentation output");
        }

        int displayOrder = regionRepository.countByProjectId(projectId);
        String resolvedLabel = (label != null && !label.isBlank())
                ? label
                : "Region " + (displayOrder + 1);

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(resolvedLabel)
                .category(RegionCategory.MANUAL)
                .maskUrl(maskUrl)
                .maskData(maskUrl)
                .displayOrder(displayOrder)
                .build();

        return regionRepository.save(region);
    }

    // ========================================================================
    // SHARED HELPERS
    // ========================================================================

    private String startSam2Prediction(Map<String, Object> input) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        boolean hasPinnedVersion = sam2ModelVersion != null && !sam2ModelVersion.isBlank();
        Map<String, Object> body = hasPinnedVersion
                ? Map.of("version", sam2ModelVersion, "input", input)
                : Map.of("input", input);
        String endpoint = hasPinnedVersion
                ? REPLICATE_BASE + "/predictions"
                : REPLICATE_BASE + "/models/meta/sam-2/predictions";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return (String) response.getBody().get("id");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 401/403 = bad token (config problem, will never recover by retrying);
            // 429 = rate limited (transient). Log them distinctly so ops can tell.
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("Replicate rejected our API token ({}). Check REPLICATE_API_TOKEN.",
                        e.getStatusCode());
            } else if (e.getStatusCode().value() == 429) {
                log.warn("Replicate rate limit hit while starting SAM 2 prediction");
            } else {
                log.error("SAM 2 prediction request rejected: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
            }
            return null;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("Replicate server error starting SAM 2 prediction: {}", e.getStatusCode());
            return null;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("Replicate unreachable or timed out starting SAM 2 prediction: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to start SAM 2 prediction: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilDone(String predictionId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + replicateApiToken);

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            String status = (String) body.get("status");
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("Prediction terminal status: {}", status);
                return null;
            }
        }
        return null;
    }

    /** Extracts the first mask URL from a SAM 2 single-point output. */
    @SuppressWarnings("unchecked")
    private static String extractFirstMaskUrl(Object output) {
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String url) return url;
        } else if (output instanceof Map<?, ?> map) {
            Object masks = map.get("individual_masks");
            if (masks instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof String url) return url;
            }
        } else if (output instanceof String url) {
            return url;
        }
        return null;
    }

    /**
     * Loads the project's UploadedImage and ensures width/height are
     * cached. Click-to-segment scales normalized clicks against these
     * dimensions, so they must be available when the manual flow runs.
     */
    private UploadedImage loadAndEnsureDimensions(String projectId) throws java.io.IOException {
        String imageId = projectRepository.findImageIdById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project has no image: " + projectId));
        UploadedImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + imageId));

        if (image.getWidth() == null || image.getHeight() == null) {
            byte[] bytes = storageService.load(image.getStorageKey());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new java.io.IOException("Could not decode image bytes: " + image.getStorageKey());
            }
            image.setWidth(decoded.getWidth());
            image.setHeight(decoded.getHeight());
            image = imageRepository.save(image);
            log.info("Cached dimensions for image {}: {}x{}",
                    image.getId(), decoded.getWidth(), decoded.getHeight());
        }
        return image;
    }

    private void persistCleanedImageKey(String projectId, String storageKey) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setCleanedImageStorageKey(storageKey);
            projectRepository.save(p);
        });
    }

    private void markSegmented(String projectId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.SEGMENTED);
            p.setFailureReason(null);
            projectRepository.save(p);
        });
    }

    private void markFailed(String projectId, String reason) {
        log.error("Segmentation failed for project {}: {}", projectId, reason);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.FAILED);
            p.setFailureReason(reason);
            projectRepository.save(p);
        });
    }
}
