package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.AccessExpiredException;
import com.gridstore.huevista.common.exception.ProcessingInterruptedException;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.exception.StorageException;
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
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final StorageService storageService;
    private final SegmentationService segmentationService;
    private final CustomerEntitlementService entitlementService;
    private final ProjectAccessPolicy projectAccessPolicy;
    private final com.gridstore.huevista.auth.service.JwtService jwtService;
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final OrgMembershipRepository orgMembershipRepository;
    private final com.gridstore.huevista.billing.service.BillingService billingService;
    private final com.gridstore.huevista.notification.EmailSender emailSender;

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
                // COUNT, not a full fetch — naming a project must not load every row the user owns.
                : "Project " + (projectRepository.countByUserId(userId) + 1);

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
    public List<ProjectSummaryResponse> getUserProjects(String userId, int page, int size) {
        entitlementService.assertAccessValid(userId);
        // Clamp instead of rejecting: page >= 0, 1 <= size <= 200.
        return projectRepository.findByUserIdWithImage(
                        userId, org.springframework.data.domain.PageRequest.of(
                                Math.max(0, page), Math.min(Math.max(1, size), 200))).stream()
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
        findOwned(userId, projectId); // ownership check

        for (RegionColorUpdate update : updates) {
            regionRepository.updateAppliedColor(
                    update.getRegionId(), projectId, update.getShadeCode(), update.getHexCode());
        }

        return toResponse(projectRepository.findById(projectId).orElseThrow());
    }

    /**
     * Rename / re-describe a project. PATCH semantics: only non-null fields are
     * applied, so the frontend can send just the field being edited. A provided
     * name must be non-blank — an unnamed project can't be found again on the
     * dashboard.
     */
    @Transactional
    public ProjectResponse updateProjectDetails(String userId, String projectId, UpdateProjectRequest request) {
        Project project = findOwned(userId, projectId);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Project name cannot be empty.");
            }
            project.setName(name);
        }
        if (request.getRoomType() != null) {
            project.setRoomType(blankToNull(request.getRoomType()));
        }
        if (request.getNotes() != null) {
            project.setNotes(blankToNull(request.getNotes()));
        }
        projectRepository.save(project);
        log.info("Project details updated: id={} user={}", projectId, userId);
        return toResponse(project);
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

        // Gate on the retailer's own AI quota WITHOUT charging yet: throws 402 when they
        // have no active subscription or have hit their monthly limit. This mirrors the
        // guest path (requestGuestSegmentation) — segmentation is the billable AI preview,
        // and the credit is only charged once the run actually produces walls
        // (SegmentationService bills on success), so a failed run stays free.
        billingService.assertAiQuotaAvailable(userId);

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

        // In local-storage mode getPublicUrl returns a relative, owner-authenticated
        // path an anonymous share viewer can't fetch — point those at the public,
        // token-scoped share image endpoints instead. S3 mode returns absolute
        // presigned URLs (already public), which are left untouched.
        String originalUrl = shareImageUrl(shareToken, "image",
                storageService.getPublicUrl(project.getImage().getStorageKey()));
        String cleanedUrl = project.getCleanedImageStorageKey() != null
                ? shareImageUrl(shareToken, "cleaned-image",
                        storageService.getPublicUrl(project.getCleanedImageStorageKey()))
                : null;
        ProjectResponse r = ProjectResponse.fromPublic(project, originalUrl);
        r.setCleanedImageUrl(cleanedUrl);
        refreshMaskUrls(r);
        return r;
    }

    /** A shared project's image bytes + content type, fetched by share token. */
    public record SharedImage(byte[] data, String contentType) {}

    /**
     * Streams a shared project's original (or cleaned) image by share token. Public:
     * the token is the capability and only that project's images are reachable. Lets
     * anonymous share viewers load the preview when the backend uses local storage,
     * where the normal image endpoint is owner-authenticated.
     */
    @Transactional(readOnly = true)
    public SharedImage getSharedImage(String shareToken, boolean cleaned) {
        Project project = projectRepository.findByShareToken(shareToken)
                .filter(p -> p.getShareExpiresAt() == null
                        || p.getShareExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found or expired."));

        String key;
        String contentType;
        if (cleaned) {
            key = project.getCleanedImageStorageKey();
            if (key == null) {
                throw new ResourceNotFoundException("No cleaned image for this project.");
            }
            contentType = contentTypeForKey(key);
        } else {
            key = project.getImage().getStorageKey();
            contentType = project.getImage().getContentType();
        }
        try {
            return new SharedImage(storageService.load(key), contentType);
        } catch (IOException e) {
            throw new StorageException("Failed to read shared image", e);
        }
    }

    /** Keep absolute (presigned) URLs as-is; rewrite a relative local-storage path to
     *  the public token-scoped share endpoint so anonymous viewers can load it. */
    private static String shareImageUrl(String token, String kind, String rawUrl) {
        if (rawUrl == null) return null;
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl;
        return "/api/share/" + token + "/" + kind;
    }

    private static String contentTypeForKey(String key) {
        String k = key.toLowerCase();
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
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
            RegionResponse response = RegionResponse.from(region);
            refreshMaskUrls(response);
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingInterruptedException("Point segmentation interrupted", e);
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
        return persistCustomMask(userId, projectId, request);
    }

    /** Delete a hand-drawn wall. Only {@code manual} regions may be removed —
     *  AI-detected surfaces are protected (400). Best-effort cleanup of the
     *  stored mask; the row delete is what matters. */
    @Transactional
    public void deleteRegion(String userId, String projectId, Long regionId) {
        findOwned(userId, projectId);
        deleteManualRegion(projectId, regionId);
    }

    private void deleteManualRegion(String projectId, Long regionId) {
        Region region = regionRepository.findByIdAndProjectId(regionId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found: " + regionId));
        if (!region.isManual()) {
            throw new IllegalArgumentException("Only hand-drawn walls can be deleted.");
        }
        String maskUrl = region.getMaskUrl();
        regionRepository.delete(region);
        if (maskUrl != null && !maskUrl.isBlank()) {
            try {
                storageService.delete(extractStorageKey(maskUrl));
            } catch (RuntimeException e) {
                log.warn("Could not delete mask for region {} (row already removed): {}", regionId, e.getMessage());
            }
        }
        log.info("Manual region deleted: project={} region={}", projectId, regionId);
    }

    /** Shared body for persisting a hand-drawn mask. {@code storageScope} is the
     *  owner key used as the storage folder (a userId or, for guests, an access code id). */
    private RegionResponse persistCustomMask(String storageScope, String projectId, CustomMaskRequest request) {
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
                    png, storageScope, category.name().toLowerCase() + "-custom.png", "image/png");
        } catch (IOException e) {
            throw new StorageException("Failed to store custom mask", e);
        }

        // Store the S3 KEY, not a presigned URL (which would expire ~60 min later).
        // The read path presigns it fresh — see resolveMaskUrl / refreshMaskUrls.
        Region region = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(label)
                .category(category)
                .maskUrl(key)
                .maskData(key)
                .displayOrder(displayOrder)
                .manual(true)
                .build());

        log.info("Custom mask region saved: project={} region={} category={}",
                projectId, region.getId(), category);
        RegionResponse response = RegionResponse.from(region);
        refreshMaskUrls(response);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GUEST (anonymous, access-code-scoped) FLOWS
    //
    //  A walk-in customer who redeemed a shop code (no account) owns a SINGLE
    //  project by their access code. Responses are the PUBLIC projection, so the
    //  guest never sees real shade codes — the issuing shop resolves those from
    //  the code. Guests can run AI wall-detection, but the Replicate cost is billed
    //  to the issuing shop's monthly AI quota; when the shop is out of credits the
    //  guest is blocked and falls back to marking walls by hand.
    // ─────────────────────────────────────────────────────────────────────────

    private static final int GUEST_PROJECT_LIMIT = 1;

    @Transactional
    public ProjectResponse createGuestProject(String accessCodeId, CreateProjectRequest request) {
        CustomerAccessCode code = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));
        if (code.isExpired()) {
            throw new AccessExpiredException("Your access has ended. Ask the shop for a new code.");
        }
        if (projectRepository.countByAccessCodeId(accessCodeId) >= GUEST_PROJECT_LIMIT) {
            throw new QuotaExceededException(
                    "Your guest access includes one project. Sign up to keep going and create more.");
        }

        UploadedImage image = imageRepository.findByIdAndAccessCodeId(request.getImageId(), accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + request.getImageId()));

        String name = (request.getName() != null && !request.getName().isBlank())
                ? request.getName() : "My room";

        Project project = projectRepository.save(Project.builder()
                .accessCode(code)
                .image(image)
                .name(name)
                .roomType(blankToNull(request.getRoomType()))
                .notes(blankToNull(request.getNotes()))
                .status(ProjectStatus.CREATED)
                .build());

        log.info("Guest project created: id={} accessCode={}", project.getId(), accessCodeId);
        return toPublicResponse(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryResponse> getGuestProjects(String accessCodeId) {
        return projectRepository.findByAccessCodeIdOrderByUpdatedAtDesc(accessCodeId).stream()
                .map(p -> ProjectSummaryResponse.from(p, storageService.getPublicUrl(p.getImage().getStorageKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getGuestProject(String accessCodeId, String projectId) {
        return toPublicResponse(findGuestOwned(accessCodeId, projectId));
    }

    @Transactional
    public ProjectResponse updateGuestRegionColors(String accessCodeId, String projectId, List<RegionColorUpdate> updates) {
        findGuestOwned(accessCodeId, projectId);
        for (RegionColorUpdate update : updates) {
            regionRepository.findByIdAndProjectId(update.getRegionId(), projectId).ifPresent(region -> {
                region.setAppliedShadeCode(update.getShadeCode());
                region.setAppliedHexCode(update.getHexCode());
                regionRepository.save(region);
            });
        }
        return toPublicResponse(projectRepository.findById(projectId).orElseThrow());
    }

    @Transactional
    public RegionResponse createGuestCustomMaskRegion(String accessCodeId, String projectId, CustomMaskRequest request) {
        findGuestOwned(accessCodeId, projectId);
        return persistCustomMask(accessCodeId, projectId, request);
    }

    @Transactional
    public void deleteGuestRegion(String accessCodeId, String projectId, Long regionId) {
        findGuestOwned(accessCodeId, projectId);
        deleteManualRegion(projectId, regionId);
    }

    /**
     * Runs AI wall-detection for a guest project. The Replicate cost is billed to the
     * issuing shop: we resolve the shop's owner and decrement their monthly AI quota
     * before kicking off the async run. If the shop has no active subscription or has
     * exhausted its quota, {@link QuotaExceededException} (HTTP 402) bubbles up and the
     * guest UI falls back to marking walls by hand.
     */
    @Transactional
    public ProjectResponse requestGuestSegmentation(String accessCodeId, String projectId) {
        Project project = findGuestOwned(accessCodeId, projectId);

        CustomerAccessCode code = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));
        if (code.isExpired()) {
            throw new AccessExpiredException("Your access has ended. Ask the shop for a new code.");
        }

        // Gate on the shop's quota WITHOUT charging yet: throws 402 when the owning
        // retailer has no active subscription or has hit their limit — the guest then
        // falls back to manual. The credit is only charged once the AI actually
        // produces walls (SegmentationService bills on success), so a failed run is free.
        String shopOwnerUserId = resolveShopOwnerUserId(code);
        billingService.assertAiQuotaAvailable(shopOwnerUserId);

        // Re-trigger guard mirrors requestSegmentation: a run stuck >5 min is treated as stale.
        if (project.getStatus() == ProjectStatus.SEGMENTING) {
            LocalDateTime updatedAt = project.getUpdatedAt();
            boolean stale = updatedAt == null || updatedAt.isBefore(LocalDateTime.now().minusMinutes(5));
            if (!stale) {
                throw new IllegalStateException("Segmentation already in progress for this project.");
            }
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

        log.info("Guest segmentation requested: project={} accessCode={} billedShopOwner={}",
                projectId, accessCodeId, shopOwnerUserId);
        return toPublicResponse(project);
    }

    /** Finds the OWNER user of the access code's organization — the account billed for guest AI. */
    private String resolveShopOwnerUserId(CustomerAccessCode code) {
        String orgId = code.getOrganization().getId();
        return orgMembershipRepository.findUserIdsByOrganizationIdAndRole(orgId, OrgMemberRole.OWNER)
                .stream()
                .findFirst()
                .orElseThrow(() -> new QuotaExceededException(
                        "This shop can't run AI previews right now. You can still mark walls by hand."));
    }

    private Project findGuestOwned(String accessCodeId, String projectId) {
        return projectRepository.findByIdAndAccessCodeId(projectId, accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    /**
     * Guest "I'm done — this is the one": stamps {@code sentToShopAt} (idempotent —
     * re-sending doesn't move the time) and gives the shop owner a best-effort email
     * heads-up. Closes the counter loop: previously the shop only learned a guest had
     * finished by polling the portal.
     */
    @Transactional
    public ProjectResponse sendGuestProjectToShop(String accessCodeId, String projectId) {
        Project project = findGuestOwned(accessCodeId, projectId);
        CustomerAccessCode code = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));
        if (code.isExpired()) {
            throw new AccessExpiredException("Your access has ended. Ask the shop for a new code.");
        }
        if (project.getSentToShopAt() == null) {
            project.setSentToShopAt(LocalDateTime.now());
            projectRepository.save(project);
            notifyShopOfSentProject(code, project);
            log.info("Guest project sent to shop: project={} accessCode={}", projectId, accessCodeId);
        }
        return toPublicResponse(project);
    }

    /** Best-effort heads-up to the issuing shop's owner — a failure never blocks the send. */
    private void notifyShopOfSentProject(CustomerAccessCode code, Project project) {
        try {
            String orgId = code.getOrganization().getId();
            orgMembershipRepository.findUserIdsByOrganizationIdAndRole(orgId, OrgMemberRole.OWNER)
                    .stream().findFirst()
                    .flatMap(userRepository::findById)
                    .ifPresent(owner -> emailSender.send(owner.getEmail(),
                            "A customer sent you their room — code " + code.getCode(),
                            "Hi,\n\n"
                                    + "The customer using access code " + code.getCode()
                                    + " just sent you their finished room (\"" + project.getName() + "\").\n\n"
                                    + "Open your Customer portal to see the colours they chose — the exact "
                                    + "shade codes are on the project.\n\n"
                                    + "— HueVista"));
        } catch (Exception e) {
            log.warn("Shop notification for sent project {} failed: {}", project.getId(), e.getMessage());
        }
    }

    /**
     * The issuing shop's view of a guest's project — FULL response WITH real shade
     * codes (the opposite of what the guest sees). Caller must have already verified
     * the requester owns/manages the code's organization. Null if the guest hasn't
     * created a project yet.
     */
    @Transactional(readOnly = true)
    public ProjectResponse getGuestProjectForShop(String accessCodeId) {
        return projectRepository.findByAccessCodeIdOrderByUpdatedAtDesc(accessCodeId).stream()
                .findFirst()
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Links the projects a guest created (owned by their access code) to a real user
     * account — called when the guest signs up. The accessCode link is kept, so the
     * issuing shop keeps visibility; the user becomes the owner and can keep working.
     * Only valid while the guest token (and thus the code) is still live.
     */
    @Transactional
    public int linkGuestProjectsToUser(String userId, String guestToken) {
        if (guestToken == null || !jwtService.isTokenValid(guestToken)
                || !"guest".equals(jwtService.extractScope(guestToken))) {
            throw new IllegalArgumentException("Invalid or expired guest session.");
        }
        String accessCodeId = jwtService.extractUserId(guestToken); // subject
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Project> projects = projectRepository.findByAccessCodeIdOrderByUpdatedAtDesc(accessCodeId);
        int claimed = 0;
        for (Project p : projects) {
            if (p.getUser() == null) {
                p.setUser(user);          // claim ownership…
                projectRepository.save(p); // …keeping accessCode so the shop still sees it.
                claimed++;
            }
        }

        // A CUSTOMER without an entitlement row is locked out of every project read
        // ("Your access is not set up"), which would freeze the projects the moment
        // they were claimed. Mirror the guest's access onto the new account: same
        // shop, same code expiry, claimed projects counted against the allowance.
        final int claimedCount = claimed;
        accessCodeRepository.findById(accessCodeId).ifPresent(code ->
                entitlementService.onGuestProjectsClaimed(user, code, claimedCount));

        log.info("Linked {} guest project(s) for code {} to user {}", claimedCount, accessCodeId, userId);
        return claimedCount;
    }

    /** Masked (public) projection — hides real shade codes from the guest. */
    private ProjectResponse toPublicResponse(Project project) {
        UploadedImage image = project.getImage();
        String originalUrl = storageService.getPublicUrl(image.getStorageKey());
        String cleanedUrl = project.getCleanedImageStorageKey() != null
                ? storageService.getPublicUrl(project.getCleanedImageStorageKey()) : null;
        ProjectResponse r = ProjectResponse.fromPublic(project, originalUrl);
        r.setCleanedImageUrl(cleanedUrl);
        refreshMaskUrls(r);
        return r;
    }

    /** Strips an optional data-URL prefix and base64-decodes the mask bytes. */
    private byte[] decodeMask(String input) {
        String b64 = input == null ? "" : input.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma >= 0) {
            b64 = b64.substring(comma + 1);
        }
        // A hand-drawn binary mask PNG is tens of KB; even a full-resolution photo
        // mask stays well under 2 MB. Reject past ~4 MB of base64 (~3 MB decoded)
        // before decoding so concurrent oversized payloads can't exhaust the heap.
        if (b64.length() > 4_000_000) {
            throw new IllegalArgumentException("Mask is too large.");
        }
        byte[] decoded;
        try {
            decoded = java.util.Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mask is not valid base64.");
        }
        // Cheap PNG signature check before handing the bytes to ImageIO.
        if (decoded.length < 8
                || (decoded[0] & 0xFF) != 0x89 || decoded[1] != 'P' || decoded[2] != 'N' || decoded[3] != 'G') {
            throw new IllegalArgumentException("Mask must be a PNG image.");
        }
        return decoded;
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
                throw new IllegalStateException("Unable to decode the project image.");
            }
            image.setWidth(decoded.getWidth());
            image.setHeight(decoded.getHeight());
            imageRepository.save(image);
            log.info("Cached dimensions for image {}: {}x{}",
                    image.getId(), decoded.getWidth(), decoded.getHeight());
        } catch (IOException e) {
            throw new StorageException("Failed to read image dimensions", e);
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
        refreshMaskUrls(r);
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
            throw new StorageException("Failed to load mask for region " + regionId, e);
        }
    }

    // Rewrites a project's region mask references into FRESH, live URLs on every read.
    // Masks are stored as S3 keys, so a presigned URL must be minted per response —
    // exactly like the original image URL — otherwise a once-generated link expires.
    private void refreshMaskUrls(ProjectResponse response) {
        if (response == null || response.getRegions() == null) return;
        response.getRegions().forEach(this::refreshMaskUrls);
    }

    private void refreshMaskUrls(RegionResponse region) {
        if (region == null) return;
        region.setMaskUrl(resolveMaskUrl(region.getMaskUrl()));
        region.setMaskData(resolveMaskUrl(region.getMaskData()));
    }

    /**
     * Turns a stored mask reference into a usable URL:
     *   - bare S3 key (new format)                 -> presign fresh
     *   - legacy presigned URL of our own bucket    -> recover the key, presign fresh
     *   - foreign URL (e.g. Replicate SAM 2 output) -> leave untouched (not ours to sign)
     */
    private String resolveMaskUrl(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        if (stored.startsWith("http://") || stored.startsWith("https://")) {
            // Only re-presign URLs that point at our own S3 bucket; pass through anything else.
            if (!stored.contains("amazonaws.com")) return stored;
            return storageService.getPublicUrl(extractStorageKey(stored));
        }
        return storageService.getPublicUrl(stored);
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
