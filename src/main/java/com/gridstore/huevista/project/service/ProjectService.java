package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.*;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.queue.SegmentationJobQueue;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final StorageService storageService;
    private final SegmentationService segmentationService;
    private final CustomerEntitlementService entitlementService;
    private final ProjectAccessPolicy projectAccessPolicy;
    private final com.gridstore.huevista.common.audit.AuditService auditService;

    @Autowired(required = false)
    private SegmentationJobQueue segmentationJobQueue;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Transactional
    public ProjectResponse createProject(String userId, CreateProjectRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Enforce the customer's project entitlement (expiry + included/granted/purchased allowance).
        // No-op for non-customer roles (retailers/distributors/admins).
        entitlementService.assertCanCreateProject(userId);

        // Retailer funnel gate: email+mobile verified, and the free trial includes
        // just one project (more require a paid plan). No-op for non-retailers.
        projectAccessPolicy.assertCanCreateProject(user);

        UploadedImage image = imageRepository.findByIdAndUserId(request.getImageId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + request.getImageId()));

        String name = (request.getName() != null && !request.getName().isBlank())
                ? request.getName()
                : "Project " + (projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).size() + 1);

        Project project = projectRepository.save(Project.builder()
                .user(user)
                .image(image)
                .name(name)
                .roomType(blankToNull(request.getRoomType()))
                .notes(blankToNull(request.getNotes()))
                .status(ProjectStatus.CREATED)
                .build());

        // Count this project against the customer's allowance (monotonic — deleting won't refund).
        entitlementService.recordProjectCreated(userId);

        log.info("Project created: id={} user={}", project.getId(), userId);
        return toResponse(project, image);
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryResponse> getUserProjects(String userId) {
        entitlementService.assertAccessValid(userId);
        return projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(p -> ProjectSummaryResponse.from(p, storageService.getPublicUrl(p.getImage().getStorageKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        return toResponse(project);
    }

    @Transactional
    public ProjectResponse updateRegionColors(String userId, String projectId, List<RegionColorUpdate> updates) {
        Project project = findOwned(userId, projectId);

        for (RegionColorUpdate update : updates) {
            regionRepository.findByIdAndProjectId(update.getRegionId(), projectId).ifPresent(region -> {
                region.setAppliedShadeCode(update.getShadeCode());
                region.setAppliedHexCode(update.getHexCode());
                regionRepository.save(region);
            });
        }

        return toResponse(projectRepository.findById(projectId).orElseThrow());
    }

    @Transactional
    public void deleteProject(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        // Best-effort cleanup so we don't orphan blobs in S3/local storage. The
        // original uploaded image is owned by UploadedImage and left intact; only
        // the per-region masks and the (project-specific) cleaned image are removed.
        for (Region region : project.getRegions()) {
            String maskUrl = region.getMaskUrl();
            if (maskUrl != null && !maskUrl.isBlank()) {
                try {
                    storageService.delete(extractStorageKey(maskUrl));
                } catch (Exception e) {
                    log.warn("Failed to delete mask for region {}: {}", region.getId(), e.getMessage());
                }
            }
        }
        String cleanedKey = project.getCleanedImageStorageKey();
        if (cleanedKey != null && !cleanedKey.isBlank()) {
            try {
                storageService.delete(cleanedKey);
            } catch (Exception e) {
                log.warn("Failed to delete cleaned image {}: {}", cleanedKey, e.getMessage());
            }
        }
        projectRepository.delete(project);
        auditService.record(userId, "PROJECT_DELETE", "PROJECT", projectId, "name=" + project.getName());
        log.info("Project deleted: id={} user={}", projectId, userId);
    }

    @Transactional
    public ProjectResponse requestSegmentation(String userId, String projectId) {
        Project project = findOwned(userId, projectId);

        // Allow re-triggering if the previous run never finished (e.g. it
        // crashed, the worker JVM restarted, or an upstream API like Gemini
        // returned a quota / payment error and bubbled out before
        // markFailed could write the status). Without this stale check, a
        // single failed run locks the project out forever and forces the
        // user to reset state by hand. 5 minutes is well past any
        // legitimate segmentation latency (typical run is 10-60s).
        if (project.getStatus() == ProjectStatus.SEGMENTING) {
            java.time.LocalDateTime updatedAt = project.getUpdatedAt();
            boolean stale = updatedAt == null
                    || updatedAt.isBefore(java.time.LocalDateTime.now().minusMinutes(5));
            if (!stale) {
                throw new IllegalStateException("Segmentation already in progress for this project.");
            }
            log.warn("Project {} stuck in SEGMENTING since {}, treating as stale and re-triggering",
                    projectId, updatedAt);
        }

        project.setStatus(ProjectStatus.SEGMENTING);
        project.setFailureReason(null);
        projectRepository.save(project);

        String imageUrl = storageService.getPublicUrl(project.getImage().getStorageKey());
        if (segmentationJobQueue != null) {
            segmentationJobQueue.enqueue(projectId, imageUrl);
        } else {
            segmentationService.segmentAsync(projectId, imageUrl);
        }

        log.info("Segmentation requested: project={}", projectId);
        return toResponse(project);
    }

    @Transactional(readOnly = true)
    public ProjectResponse getStatus(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        return toResponse(project);
    }

    @Transactional
    public ShareResponse generateShareLink(String userId, String projectId, int validDays) {
        Project project = findOwned(userId, projectId);

        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(validDays);

        project.setShareToken(token);
        project.setShareExpiresAt(expiresAt);
        projectRepository.save(project);

        String shareUrl = baseUrl + "/api/share/" + token;
        log.info("Share link generated: project={} expires={}", projectId, expiresAt);

        return ShareResponse.builder()
                .shareToken(token)
                .shareUrl(shareUrl)
                .expiresAt(expiresAt)
                .build();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getSharedProject(String shareToken) {
        Project project = projectRepository.findByShareToken(shareToken)
                .filter(p -> p.getShareExpiresAt() == null
                        || p.getShareExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found or expired."));

        String originalUrl = storageService.getPublicUrl(project.getImage().getStorageKey());
        String cleanedUrl = project.getCleanedImageStorageKey() != null
                ? storageService.getPublicUrl(project.getCleanedImageStorageKey()) : null;
        ProjectResponse r = ProjectResponse.fromPublic(project, originalUrl);
        r.setCleanedImageUrl(cleanedUrl);
        return r;
    }

    @Transactional
    public RegionResponse segmentPoint(String userId, String projectId,
                                       double x, double y, String label) {
        Project project = findOwned(userId, projectId);
        UploadedImage image = project.getImage();
        ensureDimensionsCached(image);

        String imageUrl = storageService.getPublicUrl(image.getStorageKey());
        try {
            Region region = segmentationService.segmentPointAndSave(
                    projectId, imageUrl,
                    image.getWidth(), image.getHeight(),
                    x, y, label
            );
            return RegionResponse.from(region);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Point segmentation interrupted", e);
        }
    }

    /**
     * Persists a mask the user drew by hand (polygon → PNG) as a new region.
     * No AI call: the client sends the finished mask, we decode + validate it,
     * store the PNG, and create a Region under the requested category. Mirrors
     * how auto/click segmentation persist masks (store bytes → save the URL).
     */
    @Transactional
    public RegionResponse createCustomMaskRegion(String userId, String projectId, CustomMaskRequest request) {
        findOwned(userId, projectId);

        byte[] png = decodeMask(request.getMaskBase64());
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
            if (decoded == null) {
                throw new IllegalArgumentException("Mask is not a valid image.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Mask is not a valid image.");
        }

        RegionCategory category = parseCategory(request.getCategory());
        int displayOrder = regionRepository.countByProjectId(projectId);
        String label = (request.getLabel() != null && !request.getLabel().isBlank())
                ? request.getLabel()
                : defaultLabel(category, displayOrder);

        String key;
        try {
            key = storageService.store(
                    png, userId, category.name().toLowerCase() + "-custom.png", "image/png");
        } catch (IOException e) {
            throw new RuntimeException("Failed to store custom mask", e);
        }

        String url = storageService.getPublicUrl(key);
        Region region = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(label)
                .category(category)
                .maskUrl(url)
                .maskData(url)
                .displayOrder(displayOrder)
                .build());

        log.info("Custom mask region saved: project={} region={} category={}",
                projectId, region.getId(), category);
        return RegionResponse.from(region);
    }

    /** Strips an optional data-URL prefix and base64-decodes the mask bytes. */
    private byte[] decodeMask(String input) {
        String b64 = input == null ? "" : input.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma >= 0) {
            b64 = b64.substring(comma + 1);
        }
        // A hand-drawn binary mask PNG is tens of KB; reject anything past ~12 MB of
        // base64 before decoding so an oversized payload can't exhaust the heap.
        if (b64.length() > 12_000_000) {
            throw new IllegalArgumentException("Mask is too large.");
        }
        try {
            return java.util.Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mask is not valid base64.");
        }
    }

    private RegionCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return RegionCategory.MANUAL;
        try {
            return RegionCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RegionCategory.MANUAL;
        }
    }

    private String defaultLabel(RegionCategory category, int displayOrder) {
        return switch (category) {
            case MAIN_WALL -> "Main wall";
            case ACCENT_WALL -> "Accent wall";
            case TRIM -> "Trim & Frames";
            case OTHER_WALL -> "Wall";
            case MANUAL -> "Region " + (displayOrder + 1);
        };
    }

    /**
     * Older uploads (and any future ones we don't measure at upload time) may
     * not have width/height set. SAM 2 needs pixel coordinates, so we read
     * dimensions from storage on demand and persist them back onto the image.
     */
    private void ensureDimensionsCached(UploadedImage image) {
        if (image.getWidth() != null && image.getHeight() != null) return;

        try {
            byte[] bytes = storageService.load(image.getStorageKey());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new IllegalStateException("Unable to decode image: " + image.getStorageKey());
            }
            image.setWidth(decoded.getWidth());
            image.setHeight(decoded.getHeight());
            imageRepository.save(image);
            log.info("Cached dimensions for image {}: {}x{}",
                    image.getId(), decoded.getWidth(), decoded.getHeight());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image dimensions for " + image.getStorageKey(), e);
        }
    }

    /**
     * Builds a ProjectResponse that exposes BOTH the original image URL and
     * the cleaned image URL (when ImageCleanerService has produced one).
     * Callers reaching here from inside a transactional method can rely on
     * project.image being fetched lazily; we re-look-up the image to be safe
     * when the project was loaded via a projection.
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private ProjectResponse toResponse(Project project) {
        return toResponse(project, project.getImage());
    }

    private ProjectResponse toResponse(Project project, UploadedImage image) {
        String originalUrl = storageService.getPublicUrl(image.getStorageKey());
        String cleanedUrl = project.getCleanedImageStorageKey() != null
                ? storageService.getPublicUrl(project.getCleanedImageStorageKey()) : null;
        ProjectResponse r = ProjectResponse.from(project, originalUrl);
        r.setCleanedImageUrl(cleanedUrl);
        return r;
    }

    private Project findOwned(String userId, String projectId) {
        // Full lock on expiry: a customer past their access window cannot view OR manage projects.
        entitlementService.assertAccessValid(userId);
        return projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    @Transactional(readOnly = true)
    public byte[] loadRegionMaskBytes(String userId, String projectId, Long regionId) {
        findOwned(userId, projectId);
        Region region = regionRepository.findByIdAndProjectId(regionId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found: " + regionId));
        String maskUrl = region.getMaskUrl();
        if (maskUrl == null || maskUrl.isBlank()) {
            throw new ResourceNotFoundException("Region has no mask: " + regionId);
        }
        String key = extractStorageKey(maskUrl);
        try {
            return storageService.load(key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load mask for region " + regionId, e);
        }
    }

    // Strips host + query from a presigned S3 URL to recover the object key
    // we originally wrote. Falls back to the whole path if parsing fails.
    private String extractStorageKey(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if (path == null) return url;
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
