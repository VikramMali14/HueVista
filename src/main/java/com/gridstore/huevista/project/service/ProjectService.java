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
    private final ProjectAccessService projectAccessService;
    private final ProjectBillingResolver billingResolver;
    private final com.gridstore.huevista.billing.service.ProjectCreditLedger projectCreditLedger;
    private final com.gridstore.huevista.billing.service.PricingService pricingService;
    private final com.gridstore.huevista.auth.service.JwtService jwtService;
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final OrgMembershipRepository orgMembershipRepository;
    private final com.gridstore.huevista.billing.service.BillingService billingService;
    private final com.gridstore.huevista.paint.service.ShadeCodeSchemeService shadeCodeSchemeService;
    private final com.gridstore.huevista.notification.EmailSender emailSender;

    @Autowired(required = false)
    private SegmentationJobQueue segmentationJobQueue;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Transactional
    public ProjectResponse createProject(String userId, CreateProjectRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Access-code customers: link the project to the code they redeemed so the
        // issuing retailer keeps visibility of the customer's work (the counter reads
        // the real shades from it), mirroring the anonymous-guest link. The code link
        // is never cleared once set.
        CustomerAccessCode linkedCode =
                user.getRole() == com.gridstore.huevista.auth.model.UserRole.CUSTOMER
                        ? accessCodeRepository.findFirstByUsedByUserIdOrderByCreatedAtDesc(userId).orElse(null)
                        : null;

        // Which of the three ways this project is paid for?
        //
        //  a) A shop onboarded this customer — the entitlement they redeemed carries the
        //     allowance, and the shop already reserved the image credit behind it.
        //  b) A live subscription covers it.
        //  c) Neither, so it has to be a project the account bought outright. That is the
        //     ONLY route for a self-signed-up account (which holds no entitlement and
        //     cannot buy a plan) and for a shop whose plan has lapsed.
        boolean shopEntitled = entitlementService.hasEntitlement(userId);
        boolean subscribed = pricingService.isSubscribed(userId);
        com.gridstore.huevista.billing.model.ProjectCredit credit = null;

        if (shopEntitled) {
            // Claim the customer's project slot ATOMICALLY (expiry + included/granted/purchased
            // allowance). Previously this checked the allowance here and incremented after the
            // insert, so two parallel requests could both pass on the last remaining slot.
            entitlementService.claimProjectSlot(userId);
        } else if (!subscribed) {
            credit = projectCreditLedger.claim(userId).orElseThrow(() -> noWayToPayFor(user));
        }

        try {
            // Retailer funnel gate: email+mobile verified, and the free trial includes
            // just one project (more require a paid plan, or a bought one). No-op for
            // non-retailers.
            projectAccessPolicy.assertCanCreateProject(user, credit != null);

            UploadedImage image = imageRepository.findByIdAndUserId(request.getImageId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + request.getImageId()));

            String name = (request.getName() != null && !request.getName().isBlank())
                    ? request.getName()
                    // COUNT, not a full fetch — naming a project must not load every row the user owns.
                    : "Project " + (projectRepository.countByUserId(userId) + 1);

            Project project = Project.builder()
                    .user(user)
                    .image(image)
                    .name(name)
                    .roomType(blankToNull(request.getRoomType()))
                    .notes(blankToNull(request.getNotes()))
                    .status(ProjectStatus.CREATED)
                    .accessCode(linkedCode)
                    .build();

            // A bought project carries its own validity. Opened paused when the buyer is
            // currently subscribed, so the days they paid for are banked rather than
            // silently burnt down behind a plan that was already covering them.
            if (credit != null) {
                projectAccessService.openWindow(project, credit.getValidDays(),
                        credit.getPricePaise(), subscribed);
            }

            project = projectRepository.save(project);
            if (credit != null) {
                projectCreditLedger.attach(credit.getId(), project.getId());
            }

            log.info("Project created: id={} user={} paidBy={}", project.getId(), userId,
                    shopEntitled ? "shop-code" : credit != null ? "purchase" : "subscription");
            return toResponse(project, image);
        } catch (RuntimeException failed) {
            // The credit is claimed BEFORE the project exists, because the compare-and-set
            // that decides which of two parallel creations gets it needs a row to guard and
            // a project id only exists after the insert. Hand it back explicitly if we never
            // got that far. The enclosing rollback covers this too today — the ledger joins
            // this transaction — so this is the belt to that braces: it keeps the paid-for
            // credit safe if the ledger is ever made to commit independently, which is the
            // shape these two-phase claims tend to drift towards.
            if (credit != null) {
                projectCreditLedger.release(credit.getId());
            }
            throw failed;
        }
    }

    /**
     * Nothing covers this project: no plan, no shop code, no credit bought.
     *
     * A shop and a walk-in need different answers, and the exception TYPE is what routes
     * them — SUBSCRIPTION_REQUIRED sends the studio to the plans page, which is where a
     * lapsed shop belongs, while a plain quota refusal points at the one-off purchase,
     * which is the only route a customer has (they cannot buy a plan at all). Both
     * messages name the standalone price either way, so neither is a dead end.
     */
    private RuntimeException noWayToPayFor(User user) {
        String buyOne = "Buy a single project for Rs. "
                + rupees(pricingService.projectUnsubscribedPricePaise())
                + " — it stays open for " + pricingService.projectValidDays() + " days.";
        if (user.getRole() == com.gridstore.huevista.auth.model.UserRole.RETAILER) {
            return new com.gridstore.huevista.common.exception.SubscriptionRequiredException(
                    "Your subscription has ended. Subscribe to keep creating projects, or "
                    + buyOne.substring(0, 1).toLowerCase() + buyOne.substring(1));
        }
        return new QuotaExceededException(
                "You don't have a subscription, so each project is bought on its own. " + buyOne);
    }

    /** Paise → a rupee figure for a user-facing message ("99", "9", "50"). */
    private static String rupees(int paise) {
        return paise % 100 == 0 ? String.valueOf(paise / 100)
                : String.format(java.util.Locale.ROOT, "%.2f", paise / 100.0);
    }

    /**
     * The dashboard list. For a RETAILER this is their own rooms AND every room their
     * customers created under a code the shop issued — the shop paid an image credit per
     * assigned project, so that work belongs on their dashboard rather than only inside
     * the customer portal. Each row is tagged {@code OWN} or {@code CUSTOMER} so the
     * dashboard can filter between them.
     */
    @Transactional
    public List<ProjectSummaryResponse> getUserProjects(String userId, int page, int size) {
        entitlementService.assertAccessValid(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        boolean subscribed = pricingService.isSubscribed(userId);

        // Clamp instead of rejecting: page >= 0, 1 <= size <= 200.
        int clampedSize = Math.min(Math.max(1, size), 200);
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), clampedSize);

        List<Project> own = projectRepository.findByUserIdWithImage(userId, pageable);
        // Bring the stored windows in line with the subscription before reading them, so a
        // plan that lapsed since the last visit resumes the paid days now rather than at
        // the next nightly sweep.
        projectAccessService.reconcileAll(own, subscribed);

        List<ProjectSummaryResponse> rows = new java.util.ArrayList<>(own.stream()
                .map(p -> summarize(p, user, subscribed))
                .toList());

        if (user.getRole() == com.gridstore.huevista.auth.model.UserRole.RETAILER) {
            rows.addAll(customerRoomsFor(userId, pageable));
        }
        return rows;
    }

    /** Rooms created by this shop's customers, under codes the shop issued. */
    private List<ProjectSummaryResponse> customerRoomsFor(String retailerUserId,
                                                          org.springframework.data.domain.Pageable pageable) {
        List<String> orgIds = orgMembershipRepository.findByUserId(retailerUserId).stream()
                .map(m -> m.getOrganization().getId())
                .distinct()
                .toList();
        if (orgIds.isEmpty()) return List.of();

        return projectRepository.findByIssuingOrgIds(orgIds, retailerUserId, pageable).stream()
                .map(p -> {
                    CustomerAccessCode code = p.getAccessCode();
                    return ProjectSummaryResponse.from(
                                    p,
                                    storageService.getPublicUrl(p.getImage().getStorageKey()),
                                    p.getCleanedImageStorageKey() != null
                                            ? storageService.getPublicUrl(p.getCleanedImageStorageKey())
                                            : null)
                            .asCustomerRoom(code.getCode(), code.getId(), code.getCustomerName())
                            // The shop reads its customers' work; it never paints on it from
                            // here. Editing happens in the customer's own session.
                            .withReadOnly(true);
                })
                .toList();
    }

    private ProjectSummaryResponse summarize(Project p, User owner, boolean subscribed) {
        ProjectSummaryResponse row = ProjectSummaryResponse.from(
                p,
                storageService.getPublicUrl(p.getImage().getStorageKey()),
                p.getCleanedImageStorageKey() != null
                        ? storageService.getPublicUrl(p.getCleanedImageStorageKey())
                        : null);
        return row.withReadOnly(
                !projectAccessService.evaluate(owner.getRole(), p, subscribed).editable());
    }

    @Transactional
    public ProjectResponse getProject(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        return withAccess(userId, project, toResponse(project));
    }

    /** Attach the viewer's access to an owner-view response. */
    private ProjectResponse withAccess(String userId, Project project, ProjectResponse response) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return response;
        ProjectAccessService.Access access =
                projectAccessService.accessFor(userId, user.getRole(), project);
        return response.withAccess(!access.editable(), access.reason(),
                access.expiresAt(), access.reopenPricePaise());
    }

    /**
     * Gate for anything that changes a project. Loads the project as its owner, then
     * refuses the write when their access is view-only — a lapsed subscription, or a
     * bought project whose validity ran out.
     */
    private Project findEditable(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        projectAccessService.assertEditable(userId, user.getRole(), project);
        return project;
    }

    /**
     * Autosave path — fires on EVERY swatch click, so it must stay featherweight.
     * Returns nothing: the full project response carries every region's base64
     * mask, and echoing that back per colour change re-downloaded megabytes the
     * client already has and never read.
     */
    @Transactional
    public void updateRegionColors(String userId, String projectId, List<RegionColorUpdate> updates) {
        // Ownership AND access: a view-only project keeps showing the colours that were
        // last applied, so the autosave has to be refused here rather than quietly
        // overwriting them — otherwise the "last applied colour" the user is looking at
        // is whatever they happened to click while locked out.
        findEditable(userId, projectId);

        for (RegionColorUpdate update : updates) {
            regionRepository.updateAppliedColor(
                    update.getRegionId(), projectId, update.getShadeCode(), update.getHexCode());
        }
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
        String rawMaskKey = project.getRawMaskStorageKey();
        if (rawMaskKey != null && !rawMaskKey.isBlank()) {
            try {
                storageService.delete(rawMaskKey);
            } catch (Exception e) {
                log.warn("Failed to delete raw mask {}: {}", rawMaskKey, e.getMessage());
            }
        }
        projectRepository.delete(project);
        auditService.record(userId, "PROJECT_DELETE", "PROJECT", projectId, "name=" + project.getName());
        log.info("Project deleted: id={} user={}", projectId, userId);
    }

    @Transactional
    public ProjectResponse requestSegmentation(String userId, String projectId) {
        return requestSegmentation(userId, projectId, null);
    }

    /**
     * @param options Per-run choices, persisted on the project so the async
     *                worker (possibly another JVM reading the Redis queue) sees
     *                the same choice. maskMode ("AUTO"/"MANUAL") is open to all
     *                callers — it decides whether AI wall detection runs after
     *                the compulsory clean-up; cleanImage=false is an ADMIN
     *                testing knob (the controller strips it for other roles)
     *                that skips the image-cleaner step.
     */
    @Transactional
    public ProjectResponse requestSegmentation(String userId, String projectId,
                                               com.gridstore.huevista.project.dto.SegmentRequest options) {
        Project project = findEditable(userId, projectId);
        if (options != null && options.getCleanImage() != null) {
            project.setSkipImageClean(!options.getCleanImage());
        }
        if (options != null && options.getMaskMode() != null && !options.getMaskMode().isBlank()) {
            String mode = options.getMaskMode().trim().toUpperCase();
            if (!mode.equals("AUTO") && !mode.equals("MANUAL")) {
                throw new IllegalArgumentException("maskMode must be AUTO or MANUAL.");
            }
            project.setMaskMode(mode);
        }

        // Gate WITHOUT charging yet: throws 402 when the paying account has no active
        // subscription or has hit its monthly image limit. The payer is NOT always the
        // caller — a redeemed customer's project is billed to the shop that issued their
        // access code (the shop already reserved the credit when it generated the code),
        // because a CUSTOMER can never hold a subscription of their own. Billing this to
        // the caller made every customer run fail with "Subscribe to use AI features" —
        // a plan they are forbidden from buying. See ProjectBillingResolver.
        // The credit is only charged once the run actually completes (SegmentationService
        // bills on success), so a failed run stays free.
        ProjectBillingResolver.Target target = billingResolver.resolve(projectId)
                .orElseThrow(() -> new QuotaExceededException(
                        "This project has no account to bill. Contact support."));
        // A kiosk walk-in bought this project outright, so no subscription is consulted —
        // in EITHER direction. Gating it on the shop's plan is what let the kiosk take a
        // payment and then refuse the work when the shop's own plan had lapsed.
        if (!target.selfFunded()) {
            boolean holdsReservation = target.coveredByCode()
                    && accessCodeRepository.findById(target.accessCodeId())
                            .map(c -> c.getReservedImages() > 0).orElse(false);
            billingService.assertAiQuotaAvailable(target.billedUserId(), holdsReservation);
            // AUTO mask mode additionally needs an auto-mask credit — rejected up-front with
            // a 402 AUTO_MASK_UNAVAILABLE the frontend turns into "mark walls yourself (free)
            // or upgrade", instead of burning the clean-up on a run that can't finish.
            if (!"MANUAL".equalsIgnoreCase(project.getMaskMode())) {
                billingService.assertAutoMaskQuotaAvailable(target.billedUserId());
            }
        }

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

    @Transactional
    public ProjectResponse getStatus(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        return withAccess(userId, project, toResponse(project));
    }

    @Transactional
    public ShareResponse generateShareLink(String userId, String projectId, int validDays,
                                           java.util.List<String> brands) {
        Project project = findEditable(userId, projectId);

        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(validDays);

        project.setShareToken(token);
        project.setShareExpiresAt(expiresAt);
        // Which paint companies the share viewer may repaint with (empty = all).
        project.setShareBrandList(brands);
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
        // The share page is still the issuing shop's shopfront: it hides paint names
        // where the shop hides them, and uses the shop's own numbering where it has
        // one. The viewer has no session to resolve that from, so it travels here.
        r.setShadeCodeScheme(shadeCodeSchemeService.forSharedProject(
                project.getUser() != null ? project.getUser().getId() : null,
                project.getAccessCode() != null ? project.getAccessCode().getId() : null));
        refreshMaskUrls(r);
        // Masks too: local-storage mode leaves them as relative, owner-authenticated
        // paths an anonymous share viewer can't fetch — point those at the public,
        // token-scoped share mask endpoint (S3 presigned URLs pass through).
        if (r.getRegions() != null) {
            r.getRegions().forEach(region -> {
                String url = region.getMaskUrl();
                if (url != null && !url.isBlank()
                        && !url.startsWith("http://") && !url.startsWith("https://")) {
                    region.setMaskUrl("/api/share/" + shareToken + "/regions/" + region.getId() + "/mask");
                }
            });
        }
        return r;
    }

    /**
     * Streams a shared project's region mask by share token — public, the token is
     * the capability. Lets the share page composite (and repaint) the room in
     * local-storage mode, where the normal mask endpoint is owner-authenticated.
     */
    @Transactional(readOnly = true)
    public byte[] loadSharedRegionMaskBytes(String shareToken, Long regionId) {
        Project project = projectRepository.findByShareToken(shareToken)
                .filter(p -> p.getShareExpiresAt() == null
                        || p.getShareExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found or expired."));
        Region region = regionRepository.findByIdAndProjectId(regionId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Region not found: " + regionId));
        String maskUrl = region.getMaskUrl();
        if (maskUrl == null || maskUrl.isBlank()) {
            throw new ResourceNotFoundException("Region has no mask: " + regionId);
        }
        try {
            return storageService.load(extractStorageKey(maskUrl));
        } catch (IOException e) {
            throw new StorageException("Failed to load mask for region " + regionId, e);
        }
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
        Project project = findEditable(userId, projectId);
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
        findEditable(userId, projectId);
        return persistCustomMask(userId, projectId, request);
    }

    /**
     * Replaces an EXISTING region's mask with a hand-refined one. Unlike delete,
     * this is allowed for AI-detected regions too: it's how the user fixes a mask
     * the AI got wrong (half a pillar, an edge that overshoots) after
     * segmentation, without spending an AI call or creating a duplicate region.
     * Only the mask bytes change — the region's category, label and applied
     * colour are untouched.
     */
    @Transactional
    public RegionResponse updateRegionMask(String userId, String projectId, Long regionId, CustomMaskRequest request) {
        findEditable(userId, projectId);
        return replaceRegionMask(userId, projectId, regionId, request);
    }

    /** Delete a hand-drawn wall. Only {@code manual} regions may be removed —
     *  AI-detected surfaces are protected (400). Best-effort cleanup of the
     *  stored mask; the row delete is what matters. */
    @Transactional
    public void deleteRegion(String userId, String projectId, Long regionId) {
        findEditable(userId, projectId);
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

    /** Shared body for replacing an existing region's mask (signed-in and guest).
     *  Validates + stores the new PNG, repoints the region at it, and best-effort
     *  deletes the old stored mask. Works for any region the caller owns —
     *  AI-detected or hand-drawn — since refining an AI mask is the whole point. */
    private RegionResponse replaceRegionMask(String storageScope, String projectId, Long regionId, CustomMaskRequest request) {
        Region region = regionRepository.findByIdAndProjectId(regionId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found: " + regionId));

        byte[] png = decodeMask(request.getMaskBase64());
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
            if (decoded == null) {
                throw new IllegalArgumentException("Mask is not a valid image.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Mask is not a valid image.");
        }

        String oldMask = region.getMaskUrl();
        String key;
        try {
            key = storageService.store(
                    png, storageScope, region.getCategory().name().toLowerCase() + "-edited.png", "image/png");
        } catch (IOException e) {
            throw new StorageException("Failed to store edited mask", e);
        }

        // Repoint at the fresh key (stored as a KEY, presigned per read — see resolveMaskUrl).
        region.setMaskUrl(key);
        region.setMaskData(key);
        regionRepository.save(region);

        // Best-effort cleanup of the mask we just replaced (skip foreign URLs and
        // the new key). A failure here is harmless — the row already points at the
        // new mask.
        if (oldMask != null && !oldMask.isBlank() && !oldMask.equals(key)) {
            try {
                storageService.delete(extractStorageKey(oldMask));
            } catch (RuntimeException e) {
                log.warn("Could not delete old mask for region {}: {}", regionId, e.getMessage());
            }
        }

        log.info("Region mask replaced: project={} region={} category={}",
                projectId, regionId, region.getCategory());
        RegionResponse response = RegionResponse.from(region);
        refreshMaskUrls(response);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GUEST (anonymous, access-code-scoped) FLOWS
    //
    //  A walk-in customer who redeemed a shop code (no account) owns their projects
    //  by that access code. Responses are the PUBLIC projection, so the guest never
    //  sees real shade codes — the issuing shop resolves those from the code. Guests
    //  can run AI wall-detection, billed as the code dictates (see
    //  ProjectBillingResolver); when the payer is out of credits the guest is blocked
    //  and falls back to marking walls by hand.
    // ─────────────────────────────────────────────────────────────────────────

    /** A code that carries no quota of its own (legacy rows) still gets one project. */
    private static final int MIN_GUEST_PROJECTS = 1;

    /**
     * How many projects this code's holder may create.
     *
     * The code's OWN quota, not a constant. It was hardcoded to 1, which quietly broke
     * the thing the shop had already paid for: issuing a code for five projects reserves
     * five image credits, and if that customer arrived by the guest route they could
     * create exactly one — the other four credits sat held against the shop's plan for
     * nothing. Topping the same code up (grantExtraProjects, which exists precisely to
     * add projects to a code already in a customer's hand) reserved yet more credits and
     * still changed nothing the guest could do.
     */
    private static int guestProjectLimit(CustomerAccessCode code) {
        return Math.max(MIN_GUEST_PROJECTS, code.getProjectQuota());
    }

    @Transactional
    public ProjectResponse createGuestProject(String accessCodeId, CreateProjectRequest request) {
        CustomerAccessCode code = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));
        if (code.isExpired()) {
            throw new AccessExpiredException("Your access has ended. Ask the shop for a new code.");
        }
        int limit = guestProjectLimit(code);
        if (projectRepository.countByAccessCodeId(accessCodeId) >= limit) {
            throw new QuotaExceededException(limit == 1
                    ? "Your access includes one project. Ask the shop to add another, "
                      + "or sign up to keep going."
                    : "You've used all " + limit + " projects on your code. "
                      + "Ask the shop to add another.");
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
                .map(p -> ProjectSummaryResponse.from(
                        p,
                        storageService.getPublicUrl(p.getImage().getStorageKey()),
                        p.getCleanedImageStorageKey() != null
                                ? storageService.getPublicUrl(p.getCleanedImageStorageKey())
                                : null))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getGuestProject(String accessCodeId, String projectId) {
        return toPublicResponse(findGuestOwned(accessCodeId, projectId));
    }

    @Transactional
    public void updateGuestRegionColors(String accessCodeId, String projectId, List<RegionColorUpdate> updates) {
        // Same featherweight contract as the signed-in autosave: no response body.
        findGuestOwned(accessCodeId, projectId);
        for (RegionColorUpdate update : updates) {
            regionRepository.findByIdAndProjectId(update.getRegionId(), projectId).ifPresent(region -> {
                region.setAppliedShadeCode(update.getShadeCode());
                region.setAppliedHexCode(update.getHexCode());
                regionRepository.save(region);
            });
        }
    }

    @Transactional
    public RegionResponse createGuestCustomMaskRegion(String accessCodeId, String projectId, CustomMaskRequest request) {
        findGuestOwned(accessCodeId, projectId);
        return persistCustomMask(accessCodeId, projectId, request);
    }

    @Transactional
    public RegionResponse updateGuestRegionMask(String accessCodeId, String projectId, Long regionId, CustomMaskRequest request) {
        findGuestOwned(accessCodeId, projectId);
        return replaceRegionMask(accessCodeId, projectId, regionId, request);
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

        // A KIOSK code was paid for by this customer at the store link, so the shop's plan
        // is not consulted at all — they bought the project, and the shop is neither the
        // payer nor a gate. Gating it here is what allowed the kiosk to take money and
        // then refuse the work because the SHOP's subscription had lapsed, with no refund
        // path behind it.
        //
        // For a shop-issued code the shop IS the payer, so gate on their quota WITHOUT
        // charging yet: 402 when the owning retailer has no active subscription or has hit
        // their limit, and the guest falls back to manual. A code still holding a reserved
        // credit passes the allowance half of the gate — the shop already paid for this
        // project when it generated the code, so re-checking the limit would block work
        // already bought. The credit is only charged once the AI actually produces walls
        // (SegmentationService bills on success), so a failed run is free.
        if (!code.isSelfFunded()) {
            String shopOwnerUserId = resolveShopOwnerUserId(code);
            billingService.assertAiQuotaAvailable(shopOwnerUserId, code.getReservedImages() > 0);
            // Guest runs are always fully automatic (clean-up + AI wall detection), so the
            // shop's plan must also cover an auto-mask credit; when it doesn't the guest
            // falls back to marking walls by hand exactly like on an image-quota 402.
            billingService.assertAutoMaskQuotaAvailable(shopOwnerUserId);
        }

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

        log.info("Guest segmentation requested: project={} accessCode={} paidBy={}",
                projectId, accessCodeId, code.isSelfFunded() ? "customer (kiosk)" : "issuing shop");
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
     * EVERY room created against a code — the shop's real view, newest first, WITH
     * real shade codes. A retailer-assigned code can carry several projects (they
     * paid an image per project), so answering with just the first one hid the rest
     * of the order from the counter. Caller must have already verified the requester
     * owns/manages the code's organization. Empty when nothing has been created yet.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjectsForShop(String accessCodeId) {
        return projectRepository.findByAccessCodeIdOrderByUpdatedAtDesc(accessCodeId).stream()
                .map(this::toResponse)
                .toList();
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
        if (project.getRawMaskStorageKey() != null) {
            r.setRawMaskUrl(storageService.getPublicUrl(project.getRawMaskStorageKey()));
        }
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
