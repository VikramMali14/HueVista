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
import com.gridstore.huevista.project.dto.ColourBoardResponse;
import com.gridstore.huevista.project.dto.CreateRenderRequest;
import com.gridstore.huevista.project.dto.ProjectComboResponse;
import com.gridstore.huevista.project.dto.ProjectRenderResponse;
import com.gridstore.huevista.project.dto.RecordColourBoardRequest;
import com.gridstore.huevista.project.queue.SegmentationJobQueue;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final com.gridstore.huevista.billing.service.FreeTierService freeTierService;
    private final com.gridstore.huevista.billing.service.PdfQuotaService pdfQuotaService;
    private final ProjectBoardService boardService;
    private final ProjectRenderService renderService;
    private final com.gridstore.huevista.paint.service.ShadeCodeSchemeService shadeCodeSchemeService;
    private final com.gridstore.huevista.paint.repository.ShadeRepository shadeRepository;
    private final com.gridstore.huevista.notification.EmailSender emailSender;
    private final com.gridstore.huevista.account.service.BrandAccessService brandAccessService;
    private final com.gridstore.huevista.account.service.FeatureAccessService featureAccessService;
    /** Where a link handed to a human points — the website, not this API. */
    private final com.gridstore.huevista.common.web.SiteUrls siteUrls;

    @Autowired(required = false)
    private SegmentationJobQueue segmentationJobQueue;

    @Transactional
    public ProjectResponse createProject(String userId, CreateProjectRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Settle the free tier's cycle BEFORE the "who pays for this?" decision below,
        // not inside the charge that follows it. That decision reads whether a plan is in
        // force, so a shop whose free month rolled over last night — or one that has
        // dropped back to the free tier after a plan lapsed — would otherwise be routed
        // down the buy-a-project branch and asked to pay for something it already has.
        // No-op for anyone who is not a retailer on the free tier.
        freeTierService.ensureCurrentCycle(userId);

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
        Payment payment = claimPayment(user);
        boolean subscribed = payment.subscribed();
        com.gridstore.huevista.billing.model.ProjectCredit credit = payment.credit();

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
                    .rendersAllowed(includedRenders(payment.shopEntitled() || linkedCode != null))
                    .build();

            // A bought project carries its own validity. Opened paused when the buyer is
            // currently subscribed, so the days they paid for are banked rather than
            // silently burnt down behind a plan that was already covering them.
            if (credit != null) {
                projectAccessService.openWindow(project, credit.getValidDays(),
                        credit.getPointsSpent(), subscribed);
            }

            project = projectRepository.save(project);
            if (credit != null) {
                projectCreditLedger.attach(credit.getId(), project.getId());
            }
            // Work under a shop's code is the SHOP's to pay for, not this account's: the
            // shop reserved a credit per assigned project when it generated the code, so
            // this spends that hold rather than taking a second one.
            if (linkedCode != null) {
                spendShopCredit(linkedCode);
            }

            log.info("Project created: id={} user={} paidBy={}", project.getId(), userId, payment.describe());
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
     * How many AI images a new project includes — one, or none when a SHOP paid for it.
     *
     * <p>An account that paid for its own room gets the picture at the end of it. A room a
     * shop gave away is a different bargain: the shop bought the room, out of its own
     * monthly quota, so that its customer can try colours and walk out with a colour board.
     * The photorealistic image is the expensive part and nobody has paid for it — so it is
     * not included, and the customer buys it with AI credits when they want it.
     *
     * <p>Handing one out anyway is what made the shop's quota pay for a model call it never
     * agreed to, on every code it issued and every project it granted.
     *
     * <p>Applies to projects created from here on. Rooms that already exist keep whatever
     * allowance they were created with, including shop-funded ones with an unspent image:
     * that image was promised when the room was made, and taking it back retroactively
     * would break a promise to settle a pricing question.
     */
    private static int includedRenders(boolean shopFunded) {
        return shopFunded ? 0 : 1;
    }

    /**
     * Which of the three ways a new project is paid for, already charged.
     *
     * @param credit non-null only on the bought-outright route, and the ONLY one that
     *               can be handed back — the other two are conditional UPDATEs that the
     *               enclosing transaction rolls back on its own.
     */
    private record Payment(boolean shopEntitled, boolean subscribed,
                           com.gridstore.huevista.billing.model.ProjectCredit credit) {
        String describe() {
            return shopEntitled ? "shop-code" : credit != null ? "purchase" : "subscription";
        }
    }

    /**
     * Charge one project to whoever is actually paying for this account, and say which
     * of the three routes it went down.
     *
     * <ul>
     *   <li>A shop onboarded this customer — the entitlement they unlocked carries the
     *       allowance, and the shop already reserved the image credit behind it.</li>
     *   <li>A live subscription covers it.</li>
     *   <li>Neither, so it has to be a project the account bought outright. That is the
     *       ONLY route for a self-signed-up account (which holds no entitlement and
     *       cannot buy a plan) and for a shop whose plan has lapsed.</li>
     * </ul>
     *
     * Every route charges ATOMICALLY, before the project row exists: the compare-and-set
     * that decides which of two parallel requests gets the last credit needs a row to
     * guard, and two creations must never both take it. The charge runs in the CALLER's
     * transaction, so anything that fails afterwards rolls it back.
     *
     * Shared by every way a project comes into being — uploading a photo, and claiming a
     * shared room — so that "one project" means the same thing and costs the same thing
     * however the room got here.
     */
    private Payment claimPayment(User user) {
        String userId = user.getId();
        boolean shopEntitled = entitlementService.hasEntitlement(userId);
        boolean subscribed = pricingService.isSubscribed(userId);
        if (shopEntitled) {
            entitlementService.claimProjectSlot(userId);
            return new Payment(true, subscribed, null);
        }
        if (subscribed) {
            // The plan's project credit is spent HERE, at creation — the moment the shop
            // commits to a room — rather than when the AI later runs. Charging at the run
            // meant "15 projects a month" gated nothing a shop could see: it could create
            // any number, and only discovered the ceiling once a photo was already
            // uploaded and a customer was watching.
            billingService.reserveProjectUsage(userId);
            return new Payment(false, true, null);
        }
        return new Payment(false, false,
                projectCreditLedger.claim(userId).orElseThrow(() -> noWayToPayFor(user)));
    }

    /**
     * Take a copy of a shared room into the viewer's own account, for one project.
     *
     * The room already exists: it was photographed, cleaned and masked when its owner
     * made it, and none of that work is repeated here. What the viewer is buying is a
     * room of their own to repaint and keep — the same unit of billing as any other
     * project, charged the same way, because "one project" should not mean two different
     * things depending on how the room arrived.
     *
     * The BYTES are duplicated rather than pointed at, which is the difference between
     * this and starting from the free-project library. A library template is a shared
     * asset with a guard around it ({@code deleteOwnedBlob} skips library keys); a shared
     * room belongs to the person who made it, and they may delete it tomorrow. Pointing
     * at their storage keys would leave every copy of the room broken behind them, and
     * would hand one account's blobs to another's project — so each copy gets its own
     * files, owned outright by the account that paid for it.
     *
     * Born SEGMENTED: its walls are already there and there is nothing for the pipeline
     * to do. No AI runs, no auto-mask credit is spent.
     */
    @Transactional
    public ProjectResponse claimSharedProject(String userId, String shareToken) {
        Project source = projectRepository.findByShareToken(shareToken)
                .filter(p -> p.getShareExpiresAt() == null
                        || p.getShareExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found or expired."));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Their own room, reached through their own link. Charging for a copy of
        // something they already own would be taking a project for nothing.
        if (source.getUser() != null && userId.equals(source.getUser().getId())) {
            throw new IllegalStateException("This is already your room — open it from your projects.");
        }

        freeTierService.ensureCurrentCycle(userId);

        CustomerAccessCode linkedCode =
                user.getRole() == com.gridstore.huevista.auth.model.UserRole.CUSTOMER
                        ? accessCodeRepository.findFirstByUsedByUserIdOrderByCreatedAtDesc(userId).orElse(null)
                        : null;

        Payment payment = claimPayment(user);
        com.gridstore.huevista.billing.model.ProjectCredit credit = payment.credit();

        try {
            projectAccessPolicy.assertCanCreateProject(user, credit != null);

            UploadedImage sourceImage = source.getImage();
            UploadedImage image = imageRepository.save(UploadedImage.builder()
                    .user(user)
                    .originalFilename(sourceImage.getOriginalFilename())
                    .storageKey(copyBlob(sourceImage.getStorageKey(), userId,
                            "room", sourceImage.getContentType()))
                    .contentType(sourceImage.getContentType())
                    .fileSize(sourceImage.getFileSize())
                    .width(sourceImage.getWidth())
                    .height(sourceImage.getHeight())
                    .imageType(sourceImage.getImageType())
                    .build());

            Project project = Project.builder()
                    .user(user)
                    .image(image)
                    .name(source.getName())
                    .roomType(source.getRoomType())
                    // SEGMENTED, not CREATED: the walls arrive with it.
                    .status(ProjectStatus.SEGMENTED)
                    .cleanedImageStorageKey(
                            copyBlob(source.getCleanedImageStorageKey(), userId, "cleaned", "image/jpeg"))
                    .accessCode(linkedCode)
                    .rendersAllowed(includedRenders(payment.shopEntitled() || linkedCode != null))
                    .build();

            if (credit != null) {
                projectAccessService.openWindow(project, credit.getValidDays(),
                        credit.getPointsSpent(), payment.subscribed());
            }

            project = projectRepository.save(project);
            if (credit != null) {
                projectCreditLedger.attach(credit.getId(), project.getId());
            }

            List<Region> copies = new ArrayList<>();
            for (Region r : source.getRegions()) {
                // Both columns carry the key, matching how the pipeline writes its own
                // regions; the read path presigns whichever it finds.
                String maskKey = copyBlob(extractStorageKey(r.getMaskUrl()), userId, "mask", "image/png");
                copies.add(Region.builder()
                        .project(project)
                        .label(r.getLabel())
                        .category(r.getCategory())
                        .maskUrl(maskKey != null ? maskKey : r.getMaskUrl())
                        .maskData(maskKey != null ? maskKey : r.getMaskData())
                        .appliedShadeCode(r.getAppliedShadeCode())
                        .appliedHexCode(r.getAppliedHexCode())
                        .displayOrder(r.getDisplayOrder())
                        .manual(r.isManual())
                        .build());
            }
            regionRepository.saveAll(copies);

            if (linkedCode != null) {
                spendShopCredit(linkedCode);
            }

            auditService.record(userId, "SHARED_PROJECT_CLAIMED", "PROJECT", project.getId(),
                    "from=" + source.getId() + " walls=" + copies.size());
            log.info("Shared project {} claimed as {} by {} ({} walls, no AI, paidBy={})",
                    source.getId(), project.getId(), userId, copies.size(), payment.describe());

            project.setRegions(copies);
            return toResponse(project, image);
        } catch (RuntimeException failed) {
            if (credit != null) {
                projectCreditLedger.release(credit.getId());
            }
            throw failed;
        }
    }

    /**
     * Duplicate one stored object under the claiming account, returning the new key.
     *
     * Null in, null out — a room with no cleaned image and a region with no mask are
     * both ordinary. A copy that FAILS is not ordinary, so it throws: a claimed room
     * missing its walls is worse than a claim that did not happen, and the enclosing
     * transaction hands the project credit back.
     */
    private String copyBlob(String sourceKey, String userId, String what, String contentType) {
        if (sourceKey == null || sourceKey.isBlank()) return null;
        if (sourceKey.startsWith("http://") || sourceKey.startsWith("https://")) {
            // A foreign URL (a Replicate output that was never re-stored) is not ours to
            // copy. Leave the row pointing where it pointed.
            if (!sourceKey.contains("amazonaws.com")) return null;
        }
        String key = extractStorageKey(sourceKey);
        try {
            byte[] bytes = storageService.load(key);
            return storageService.store(bytes, userId, what + extensionOf(key), contentType);
        } catch (IOException e) {
            throw new StorageException("Could not copy the shared room's " + what + ".", e);
        }
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot > -1 && dot > key.lastIndexOf('/') ? key.substring(dot) : ".png";
    }

    /**
     * Spend the credit the issuing shop is holding for one access-code project.
     *
     * The hold comes off the CODE first; only if that succeeds may the matching hold move
     * on the subscription, so the two counters — which are the same reservation counted in
     * two places — can never drift. A code with no hold left (a legacy one, or more
     * projects than were reserved) falls back to a normal charge, so work is never
     * silently free. Best-effort throughout: a missing org or owner means nobody to bill,
     * and that must not fail a project the customer is entitled to.
     */
    private void spendShopCredit(CustomerAccessCode code) {
        if (code.isSelfFunded()) {
            return; // the walk-in paid at the kiosk; the shop's plan was never part of it
        }
        try {
            String ownerId = resolveShopOwnerUserId(code);
            if (ownerId == null) return;
            boolean spentHold = accessCodeRepository.consumeReservedProject(code.getId()) == 1
                    && billingService.consumeReservedProject(ownerId);
            if (!spentHold) {
                billingService.incrementProjectUsage(ownerId);
            }
            log.info("Access-code project charged to {}: {} (code={})",
                    ownerId, spentHold ? "held-credit" : "charged", code.getId());
        } catch (RuntimeException e) {
            log.warn("Could not charge access-code project (code={}): {}", code.getId(), e.getMessage());
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
        int days = pricingService.projectValidDays();
        if (user.getRole() == com.gridstore.huevista.auth.model.UserRole.RETAILER) {
            return new com.gridstore.huevista.common.exception.SubscriptionRequiredException(
                    "Your subscription has ended. Subscribe to keep creating projects, or spend "
                    + pricingService.pointsPriceProject(user.getId()) + " points on a single project — it "
                    + "stays open for " + days + " days.");
        }
        // A customer who reaches here holds no shop entitlement at all — one that does is
        // served by its shop above, and one that has run out is told to ask that shop.
        // So this is someone who signed up on their own, with nobody to ask. Sending them
        // to "your paint shop" named a party that does not exist for them and left the
        // account with no route forward whatsoever; they buy the project directly.
        return new QuotaExceededException(
                "Buy a project for ₹" + rupees(pricingService.projectPricePaise(user.getId()))
                + " to start this one — it stays open for " + days + " days, and you can reopen it "
                + "for ₹" + rupees(pricingService.reopenPricePaise()) + " for another " + days
                + ". If a paint shop gave you an access code, redeem that instead.");
    }

    /** Paise → a rupee figure for a user-facing message ("99", "9", "50"). */
    private static String rupees(int paise) {
        return paise % 100 == 0 ? String.valueOf(paise / 100)
                : String.format(java.util.Locale.ROOT, "%.2f", paise / 100.0);
    }

    /** Largest merged window a single request will assemble — see {@link #getUserProjects}. */
    private static final int MAX_MERGE_WINDOW = 1_000;
    /** Largest page the list endpoint will hand back. */
    public static final int MAX_PAGE_SIZE = 500;

    /**
     * The dashboard list. For a RETAILER this is their own rooms AND every room their
     * customers created under a code the shop issued — the shop paid an image credit per
     * assigned project, so that work belongs on their dashboard rather than only inside
     * the customer portal. Each row is tagged {@code OWN} or {@code CUSTOMER} so the
     * dashboard can filter between them.
     *
     * <h2>Paging across two sources</h2>
     * A retailer's page is drawn from two queries, and the page has to mean the same
     * thing as it would from one. Handing the SAME {@code Pageable} to both was neither:
     * it returned up to 2×{@code size} rows, and "page 1" meant the second page of each
     * list independently — so a row could sit on both pages or on neither, depending on
     * how the two interleaved by date.
     *
     * Both queries order by {@code updatedAt DESC}, so the fix is to read a window
     * covering everything up to the end of the requested page from each, merge on that
     * same key, and cut the requested slice out of the result. One page in, one page out.
     *
     * The window is bounded by {@link #MAX_MERGE_WINDOW}: deep paging over a merged list
     * costs a row read per source per page, and this is a dashboard, not an export.
     * Beyond that depth the list reads empty rather than growing without limit.
     *
     * {@code size} is a cap on the WHOLE response now rather than on each source, which
     * on its own would have shown a busy shop less than before. The ceiling is raised to
     * {@link #MAX_PAGE_SIZE} to cover that: the old shape could return 2×200 rows across
     * the two sources, so anything less than 400 would have been a reduction.
     */
    @Transactional
    public List<ProjectSummaryResponse> getUserProjects(String userId, int page, int size) {
        entitlementService.assertAccessValid(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        boolean subscribed = pricingService.isSubscribed(userId);

        // Clamp instead of rejecting: page >= 0, 1 <= size <= MAX_PAGE_SIZE.
        int clampedPage = Math.max(0, page);
        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        boolean retailer = user.getRole() == com.gridstore.huevista.auth.model.UserRole.RETAILER;

        // A non-retailer has one source, so the requested page IS the query's page and
        // nothing needs merging. Only the two-source case pays for the wider window.
        long offset = (long) clampedPage * clampedSize;
        if (offset >= MAX_MERGE_WINDOW) {
            return List.of();
        }
        int windowSize = retailer
                ? (int) Math.min(MAX_MERGE_WINDOW, offset + clampedSize)
                : clampedSize;
        var pageable = org.springframework.data.domain.PageRequest.of(
                retailer ? 0 : clampedPage, windowSize);

        List<Project> own = projectRepository.findByUserIdWithImage(userId, pageable);
        // Bring the stored windows in line with the subscription before reading them, so a
        // plan that lapsed since the last visit resumes the paid days now rather than at
        // the next nightly sweep.
        projectAccessService.reconcileAll(own, subscribed);

        List<ProjectSummaryResponse> rows = new java.util.ArrayList<>(own.stream()
                .map(p -> summarize(p, user, subscribed))
                .toList());

        if (!retailer) {
            return rows;
        }

        rows.addAll(customerRoomsFor(userId, pageable));
        // Merge on the key both queries already sort by. Nulls last so a row with no
        // timestamp sinks to the bottom instead of colonising the top of the dashboard.
        rows.sort(java.util.Comparator.comparing(ProjectSummaryResponse::getUpdatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return rows.stream().skip(offset).limit(clampedSize).toList();
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

    // ─── Admin: every room, whoever owns it ──────────────────────────────────

    /**
     * Rooms across the whole platform, newest first, for the admin mask browser.
     *
     * <p>Callers are gated to ROLE_ADMIN at the controller. There is no ownership filter
     * on purpose: the failure this exists to investigate — a run that put the walls in
     * the wrong places — is invisible to the backend and only ever reported by the person
     * whose room it is, so the admin has to be able to open somebody else's room to see
     * what they saw.
     *
     * @param q free text matched against the room name or id, the owner's name or email,
     *          the shop's name, or the access code. Blank returns everything.
     */
    @Transactional(readOnly = true)
    public List<AdminProjectRow> searchAllProjects(String q, int page, int size) {
        String pattern = (q == null || q.isBlank())
                ? "" : "%" + q.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        var pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, size), 200));
        return projectRepository.searchAll(pattern, pageable).stream()
                .map(AdminProjectRow::from)
                .toList();
    }

    /**
     * One room's full detail — regions, masks, both canvases — with no ownership check.
     *
     * <p>Marked read-only in the response whatever its real state is. An admin is here to
     * look at what the pipeline produced, not to paint in somebody else's room, and the
     * studio disables every write path on that flag. The room's own access window is
     * deliberately not consulted: a lapsed or closed project is exactly the kind that
     * gets reported, and it would be no use if the report could not then be opened.
     */
    @Transactional(readOnly = true)
    public ProjectResponse getProjectAsAdmin(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        return toResponse(project).withAccess(true, "Admin view — read only.", null, 0, 0);
    }

    /** Attach the viewer's access to an owner-view response. */
    private ProjectResponse withAccess(String userId, Project project, ProjectResponse response) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return response;
        ProjectAccessService.Access access =
                projectAccessService.accessFor(userId, user.getRole(), project);
        return response.withAccess(!access.editable(), access.reason(),
                access.expiresAt(), access.reopenPricePoints(), access.reopenPricePaise());
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

    // ─── Colour boards and closing ───────────────────────────────────────────

    /**
     * Charge for a colour board this project just handed over, record what was on it, and
     * close the project when it was the last one.
     *
     * Gated on {@code findEditable} rather than {@code findOwned}: handing over a board is
     * something a project DOES, and a view-only project — lapsed, unsubscribed or already
     * closed — has nothing left to hand over. It also means the "already closed" refusal
     * arrives as the studio's own view-only message rather than as a second, differently
     * worded one.
     */
    @Transactional
    public ColourBoardResponse recordColourBoard(String userId, String projectId,
                                                 RecordColourBoardRequest request) {
        Project project = findEditable(userId, projectId);
        return boardService.recordBoard(project, request,
                () -> pdfQuotaService.reserveForUser(userId));
    }

    /**
     * The guest twin, billed to whoever the access code says pays.
     *
     * A guest room records its boards but never CLOSES on them. Closing exists to unlock
     * the render, and the render page is behind a sign-in a walk-in does not have — so it
     * would take the studio away from them and give nothing back. How many boards they get
     * is already governed by the code they were sold.
     */
    @Transactional
    public ColourBoardResponse recordGuestColourBoard(String accessCodeId, String projectId,
                                                      RecordColourBoardRequest request) {
        Project project = findGuestOwned(accessCodeId, projectId);
        return boardService.recordBoard(project, request,
                () -> pdfQuotaService.reserveForGuest(accessCodeId), false);
    }

    /**
     * Close a project on its owner's say-so, before it has spent both boards.
     *
     * Deliberately NOT gated on {@code findEditable}. Closing an already-closed project is
     * a no-op rather than an error, and refusing it because the project is view-only would
     * mean the one action whose whole purpose is to make a project view-only could not be
     * taken on a project whose window had happened to lapse first.
     */
    @Transactional
    public ProjectResponse closeProject(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        boardService.close(project);
        return withAccess(userId, project, toResponse(project));
    }

    /** The combos this project handed over — what a closed project still shows. */
    @Transactional(readOnly = true)
    public List<ProjectComboResponse> getCombos(String userId, String projectId) {
        findOwned(userId, projectId);
        return boardService.combos(projectId);
    }

    // ─── AI renders ──────────────────────────────────────────────────────────
    //
    // Read paths, so findOwned and not findEditable: a render is made FROM a closed
    // project, and a closed project is view-only by definition. Gating these on
    // editability would make the one thing closing unlocks impossible to reach.

    @Transactional
    public ProjectRenderResponse requestRender(String userId, String projectId,
                                               CreateRenderRequest request) {
        return renderService.request(findOwned(userId, projectId), request);
    }

    @Transactional(readOnly = true)
    public List<ProjectRenderResponse> listRenders(String userId, String projectId) {
        findOwned(userId, projectId);
        return renderService.list(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectRenderResponse getRender(String userId, String projectId, String renderId) {
        findOwned(userId, projectId);
        return renderService.get(projectId, renderId);
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
            deleteOwnedBlob(region.getMaskUrl(), "mask for region " + region.getId());
        }
        deleteOwnedBlob(project.getCleanedImageStorageKey(), "cleaned image");
        deleteOwnedBlob(project.getRawMaskStorageKey(), "raw mask");
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
        if (options != null && options.getSimulateFailure() != null) {
            // Rejected loudly rather than ignored: a typo'd value on a knob whose whole
            // job is to make something fail would otherwise run an honest pipeline and
            // look like the failure path is broken. Null (the normal case) keeps
            // whatever the last run was given, exactly like skipImageClean above — an
            // explicit "NONE" is how a simulation is switched back off.
            project.setSimulatedFailure(AiFailureSimulator.parse(options.getSimulateFailure()));
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
        // No quota check here: the project's credit was taken when the project was
        // CREATED, so the run it was created for — and every retry of it — is already
        // paid for. Gating again would refuse a re-run to a shop that has since used up
        // its month, on a room it has already been charged for. What still applies is
        // findEditable above: a view-only project (lapsed plan, expired validity) cannot
        // be re-run at all.

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
        // Both of these describe the run that just ended rather than the project, so a
        // new attempt starts without them: leaving autoMaskFailed set would have the
        // studio still asking for hand-marked walls while detection is back in flight.
        project.setFailureStage(null);
        project.setAutoMaskFailed(false);
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

    /**
     * The public link for a project — created on first use, REUSED afterwards.
     *
     * Minting a fresh token on every call silently killed the link already sent. Sharing
     * is a WhatsApp-shaped action: the customer forwards it to a spouse, a builder, a
     * group. Pressing Share a second time (to change the companies, or just because the
     * dialog was reopened) invalidated the URL that was already out there, with no
     * warning and nothing to tell the recipient apart from a link that had "expired".
     * The same token is kept and its window refreshed instead; {@link #revokeShareLink}
     * is how a link is deliberately withdrawn.
     */
    @Transactional
    public ShareResponse generateShareLink(String userId, String projectId, int validDays,
                                           java.util.List<String> brands) {
        Project project = findEditable(userId, projectId);

        // A shop may only open up companies it actually carries — the same rule access
        // codes are held to. Without it the share page was a way around the distributor's
        // grant: hand out a link, and the viewer repaints with the whole catalogue.
        assertShareBrandsOfferable(userId, brands);

        String token = project.getShareToken() != null && !project.getShareToken().isBlank()
                ? project.getShareToken()
                : UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(validDays);

        project.setShareToken(token);
        project.setShareExpiresAt(expiresAt);
        // Which paint companies the share viewer may repaint with (empty = all).
        project.setShareBrandList(brands);
        projectRepository.save(project);

        // The website's page, not the API endpoint behind it — see SiteUrls.
        String shareUrl = siteUrls.on("/share/" + token);
        log.info("Share link {} for project={} expires={}",
                project.getShareToken().equals(token) ? "refreshed" : "generated", projectId, expiresAt);

        return ShareResponse.builder()
                .shareToken(token)
                .shareUrl(shareUrl)
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Withdraw a project's public link.
     *
     * The deliberate counterpart to reusing the token above: a link that was forwarded to
     * the wrong person is now revocable on purpose, rather than by the side effect of
     * pressing Share again.
     */
    @Transactional
    public void revokeShareLink(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        project.setShareToken(null);
        project.setShareExpiresAt(null);
        projectRepository.save(project);
        log.info("Share link revoked: project={}", projectId);
    }

    /** A shop can only share companies its distributor assigned it. */
    private void assertShareBrandsOfferable(String userId, java.util.List<String> brands) {
        if (brands == null || brands.isEmpty()) return;
        featureAccessService.retailerOrgOf(userId)
                .ifPresent(org -> brandAccessService.assertBrandsOfferable(org.getId(), brands));
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
        // The one code a forwarded link may carry. fromPublic() dropped the
        // manufacturer's; this puts back something that can be acted on at a counter
        // without naming the company on a page anyone might open.
        applyHvCodes(r, project);
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
     * Fill each region's HV code from the shade it has applied.
     *
     * The region row stores a shade code and a hex, not a shade id, and a shade code is
     * only unique WITHIN a company — so "L124" alone can match rows from two
     * manufacturers. The applied hex settles it: the region was painted FROM a specific
     * catalogue shade, so the one whose hex matches is that shade. With no hex match
     * (an older row, or a colour edited by hand afterwards) the code is left null
     * rather than guessed, because a wrong HV code sends someone to the counter for
     * the wrong tin — worse than sending them with none.
     *
     * One query per distinct code, and a room has a handful of walls.
     */
    private void applyHvCodes(ProjectResponse response, Project project) {
        if (response.getRegions() == null || response.getRegions().isEmpty()) return;
        java.util.Map<Long, String> appliedByRegionId = project.getRegions() == null ? java.util.Map.of()
                : project.getRegions().stream()
                        .filter(rg -> rg.getAppliedShadeCode() != null && !rg.getAppliedShadeCode().isBlank())
                        .collect(java.util.stream.Collectors.toMap(Region::getId,
                                Region::getAppliedShadeCode, (a, b) -> a));
        if (appliedByRegionId.isEmpty()) return;

        java.util.Map<String, List<com.gridstore.huevista.paint.model.Shade>> byCode = appliedByRegionId.values().stream()
                .distinct()
                .collect(java.util.stream.Collectors.toMap(code -> code.toUpperCase(),
                        shadeRepository::findByShadeCodeIgnoreCase, (a, b) -> a));

        response.getRegions().forEach(region -> {
            String code = appliedByRegionId.get(region.getId());
            if (code == null) return;
            List<com.gridstore.huevista.paint.model.Shade> hits = byCode.get(code.toUpperCase());
            if (hits == null || hits.isEmpty()) return;
            if (hits.size() == 1) {
                region.setAppliedHvCode(hits.get(0).getHvCode());
                return;
            }
            String hex = region.getAppliedHexCode();
            if (hex == null) return;
            hits.stream()
                    .filter(s -> hex.equalsIgnoreCase(s.getHexCode()))
                    .findFirst()
                    .ifPresent(s -> region.setAppliedHvCode(s.getHvCode()));
        });
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

    /**
     * Click-to-segment, against the canvas the user actually clicked.
     *
     * <p>SAM used to be sent the ORIGINAL photo while the studio was displaying the
     * CLEANED one. Every mask it returned was therefore drawn on a different picture
     * from the one the click came from, and then stretched over the cleaned canvas:
     * the wire, the parked car and the overhanging branch that the clean removed were
     * all still there as far as SAM was concerned, so it traced edges that no longer
     * exist and the wall came back with pieces missing. The generative clean also
     * shifts pixels slightly, which puts the rest of the outline out by a little
     * everywhere.
     *
     * <p>So the cleaned image is preferred, with ITS pixel size — the size matters as
     * much as the URL, because the click arrives normalised (0–1) and is multiplied by
     * the dimensions of whatever image is being sent. Projects cleaned before those
     * dimensions were recorded, and runs with no cleaned canvas at all, fall back to
     * the original photo exactly as before.
     */
    @Transactional
    public RegionResponse segmentPoint(String userId, String projectId,
                                       double x, double y, String label) {
        Project project = findEditable(userId, projectId);
        UploadedImage image = project.getImage();
        ensureDimensionsCached(image);

        boolean useCleaned = project.getCleanedImageStorageKey() != null
                && project.getCleanedImageWidth() != null
                && project.getCleanedImageHeight() != null;
        String imageUrl = storageService.getPublicUrl(
                useCleaned ? project.getCleanedImageStorageKey() : image.getStorageKey());
        int canvasWidth = useCleaned ? project.getCleanedImageWidth() : image.getWidth();
        int canvasHeight = useCleaned ? project.getCleanedImageHeight() : image.getHeight();
        try {
            Region region = segmentationService.segmentPointAndSave(
                    projectId, imageUrl,
                    canvasWidth, canvasHeight,
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

    /** Remove a wall from a project — hand-drawn or AI-detected. Best-effort cleanup
     *  of the stored mask; the row delete is what matters. */
    @Transactional
    public void deleteRegion(String userId, String projectId, Long regionId) {
        findEditable(userId, projectId);
        deleteRegionRow(projectId, regionId);
    }

    /**
     * Delete a wall, whichever way it got there.
     *
     * AI-detected surfaces used to be protected outright: only {@code manual} regions
     * could go, and everything the detector produced was permanent. That was the wrong
     * guard for the commonest thing people actually want, which is to take a wall OUT.
     * Detection routinely finds surfaces nobody wants painted — an accent wall the
     * customer has no intention of changing, a ceiling, a slab of floor read as wall —
     * and with no way to remove them the room carried dead entries for the rest of its
     * life: in the wall strip, in the palette, and on every page of the colour board.
     * The only workaround was to delete the whole project and start again, which costs
     * a fresh project credit for a problem the AI created.
     *
     * The asymmetry it was defending is real but belongs in the UI, not here. A manual
     * wall can be re-drawn in seconds; an AI wall cannot come back without re-running
     * detection, which costs a credit. That is a "are you sure" worth showing before
     * the click, not a refusal after it — and refusing left people with no route at all
     * rather than an expensive one.
     *
     * The last wall is deletable too. A room with no walls paints nothing, but it is
     * not a dead end: drawing one by hand is free and unlimited, so the recovery is
     * already there. Special-casing it would refuse the one deletion someone with a
     * single, badly-detected wall most needs to make.
     */
    private void deleteRegionRow(String projectId, Long regionId) {
        Region region = regionRepository.findByIdAndProjectId(regionId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found: " + regionId));
        String maskUrl = region.getMaskUrl();
        boolean manual = region.isManual();
        regionRepository.delete(region);
        // Library rooms share their masks with the template every copy is made from, so
        // this must not follow the key blindly — deleteOwnedBlob skips library keys, and
        // without that, one customer tidying up their copy would strip the wall out of
        // the shelf room for everybody.
        deleteOwnedBlob(maskUrl, "mask for region " + regionId);
        log.info("{} region deleted: project={} region={}",
                manual ? "Manual" : "Detected", projectId, regionId);
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
        if (!key.equals(oldMask)) {
            deleteOwnedBlob(oldMask, "previous mask for region " + regionId);
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
                // Always shop-funded: a code IS the shop paying for this room.
                .rendersAllowed(includedRenders(true))
                .build());

        // Charged to the issuing shop at creation, exactly like a signed-in one — a kiosk
        // code is skipped inside, because the walk-in already paid for it themselves.
        spendShopCredit(code);

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
        deleteRegionRow(projectId, regionId);
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
        // The shop was charged when this guest project was created, so the run is already
        // paid for and needs no gate. The code's own expiry is checked above, which is the
        // thing that actually ends a walk-in's access.

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
        // Both of these describe the run that just ended rather than the project, so a
        // new attempt starts without them: leaving autoMaskFailed set would have the
        // studio still asking for hand-marked walls while detection is back in flight.
        project.setFailureStage(null);
        project.setAutoMaskFailed(false);
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
                        "This shop has no rooms left this month. You can still mark the walls by hand."));
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

    /** The one name each surface goes by — see {@link RegionCategory#getDefaultLabel()}. */
    private String defaultLabel(RegionCategory category, int displayOrder) {
        return category == RegionCategory.MANUAL
                ? category.getDefaultLabel() + " " + (displayOrder + 1)
                : category.getDefaultLabel();
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
        ProjectResponse r = ProjectResponse.from(project, originalUrl,
                boardService.boardsPerProject(), pricingService.renderTopUpPricePaise());
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
    /**
     * Deletes a blob this project OWNS, and deliberately does nothing for one it
     * merely points at.
     *
     * A project started from the free-project library shares the template's photo
     * and masks with every other copy — those rows name the files, they do not own
     * them. Run unguarded, the ordinary tidy-up below would let the first user who
     * deleted their copy (or redrew one wall of it) delete that room out from under
     * everybody else holding it, and out of the library itself. So library keys are
     * skipped here; {@code FreeProjectLibraryService.deleteTemplate} is the only
     * thing that removes them.
     *
     * Best-effort otherwise: a stubborn blob must not fail the delete that matters,
     * which is the row.
     */
    private void deleteOwnedBlob(String urlOrKey, String what) {
        if (urlOrKey == null || urlOrKey.isBlank()) return;
        String key = extractStorageKey(urlOrKey);
        if (com.gridstore.huevista.library.FreeProjectStorage.isLibraryKey(key)) {
            log.debug("Keeping shared free-library file {} ({})", key, what);
            return;
        }
        try {
            storageService.delete(key);
        } catch (Exception e) {
            log.warn("Failed to delete {} ({}): {}", what, key, e.getMessage());
        }
    }

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
