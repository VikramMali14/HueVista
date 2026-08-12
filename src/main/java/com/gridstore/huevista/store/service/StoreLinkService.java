package com.gridstore.huevista.store.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.store.dto.CreateStoreLinkRequest;
import com.gridstore.huevista.store.dto.StoreLinkResponse;
import com.gridstore.huevista.store.dto.StorePublicInfoResponse;
import com.gridstore.huevista.store.dto.UpdateStoreLinkRequest;
import com.gridstore.huevista.store.model.StoreLink;
import com.gridstore.huevista.store.repository.StoreLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

/**
 * Retailer-managed public kiosk links. The shop prints or shares the URL; walk-in
 * customers open it, pay the flat platform kiosk price, and land straight in the guest
 * studio with a code they can re-enter from home.
 *
 * A link belongs to the SHOP, not to a window of time. It is created with nothing to
 * configure and never expires on its own — the shop is the only thing that ends it,
 * by pausing it (the printed URL keeps working the moment it is resumed) or deleting
 * it. That is why there is no validity to choose here any more: the shop was being
 * asked for 3, 7 or 14 days at creation, which describes the code a walk-in buys and
 * not the link at all, and sat next to counter-issued codes running a fixed 10.
 *
 * The retailer does NOT price the link either. That was removed with the retailer
 * revenue share: the walk-in is HueVista's customer, the whole payment is HueVista's,
 * and the shop earns closed-loop points per sale instead (see {@link StoreKioskService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreLinkService {

    private final StoreLinkRepository linkRepository;
    private final OrganizationRepository orgRepository;
    private final OrgMembershipRepository membershipRepository;
    private final com.gridstore.huevista.billing.service.PricingService pricingService;

    @Value("${razorpay.key-id:}")
    private String razorpayKeyId;

    @Value("${razorpay.key-secret:}")
    private String razorpayKeySecret;

    /**
     * How long the code a walk-in buys lasts. One platform number, not a per-shop
     * choice — see the class note. Configurable so it can be tuned in one place if a
     * kiosk customer needs longer to get home and pick up where they left off.
     */
    @Value("${app.store.code-valid-days:3}")
    private int codeValidDays;

    // Same unambiguous alphabet as access codes, lowercased for a friendlier URL.
    private static final String SLUG_ALPHABET = "abcdefghjklmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public StoreLinkResponse createLink(String requestingUserId, String orgId, CreateStoreLinkRequest request) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        if (org.getType() != OrgType.RETAILER) {
            throw new IllegalArgumentException("Store links can only be created by retailer organizations");
        }
        requireOwnerOrManager(requestingUserId, orgId);

        // Nothing to configure: the link is the shop's, permanently, and the window on
        // the code a walk-in buys is a platform number. `request` is accepted for wire
        // compatibility with clients that still send a validity, and ignored.
        StoreLink link = StoreLink.builder()
                .organization(org)
                .slug(generateUniqueSlug(org))
                .validDays(codeValidDays)
                .build();
        link = linkRepository.save(link);

        log.info("Store link created: org={} slug={} codeValidDays={}", orgId, link.getSlug(), codeValidDays);
        return describe(link);
    }

    @Transactional(readOnly = true)
    public List<StoreLinkResponse> listLinks(String requestingUserId, String orgId) {
        requireOwnerOrManager(requestingUserId, orgId);
        return linkRepository.findByOrganizationIdAndDeletedAtIsNullOrderByCreatedAtDesc(orgId).stream()
                .map(this::describe)
                .toList();
    }

    @Transactional
    public StoreLinkResponse updateLink(String requestingUserId, String linkId, UpdateStoreLinkRequest request) {
        StoreLink link = requireManagedLink(requestingUserId, linkId);

        // The validity is no longer the shop's to set — a link created before this and
        // carrying its own number keeps it, but nothing may change one now.
        if (request.getActive() != null) {
            link.setActive(request.getActive());
        }
        link = linkRepository.save(link);
        log.info("Store link updated: id={} active={}", linkId, link.isActive());
        return describe(link);
    }

    /**
     * Retire a link: its URL stops working at once and it leaves the shop's list.
     *
     * Soft, and it has to be. {@code store_payments.store_link_id} is NOT NULL, so
     * removing the row would delete the shop's record of what this link sold and the
     * points audit that explains its balance. The codes it already sold are left alone
     * too — a walk-in paid for that access, and the shop closing a counter is not a
     * reason to take it back.
     *
     * Idempotent: deleting an already-deleted link answers with the same retired link
     * rather than a 404, so a double-tap on a slow connection is not an error.
     */
    @Transactional
    public StoreLinkResponse deleteLink(String requestingUserId, String linkId) {
        StoreLink link = linkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Store link not found: " + linkId));
        requireOwnerOrManager(requestingUserId, link.getOrganization().getId());
        if (link.isDeleted()) {
            return describe(link);
        }
        link.setDeletedAt(java.time.LocalDateTime.now());
        // Belt and braces: every public path already filters on deletedAt, and this
        // makes a retired link inert to anything that only ever checked `active`.
        link.setActive(false);
        link = linkRepository.save(link);
        log.info("Store link deleted: id={} slug={} org={}",
                linkId, link.getSlug(), link.getOrganization().getId());
        return describe(link);
    }

    /** A link this user manages that has not been retired. */
    private StoreLink requireManagedLink(String requestingUserId, String linkId) {
        StoreLink link = linkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Store link not found: " + linkId));
        requireOwnerOrManager(requestingUserId, link.getOrganization().getId());
        if (link.isDeleted()) {
            throw new ResourceNotFoundException("Store link not found: " + linkId);
        }
        return link;
    }

    /** A link plus the platform price and what each sale earns the shop. */
    private StoreLinkResponse describe(StoreLink link) {
        return StoreLinkResponse.from(link)
                .withPlatformPricing(pricingService.kioskPricePaise(), pricingService.kioskBonusPoints());
    }

    /** Anonymous kiosk view of a link. 404 when the slug doesn't exist OR the shop has
     *  deleted it; a merely PAUSED link is still returned, because the page explains a
     *  pause and the shop means to come back. A deletion is not a pause. */
    @Transactional(readOnly = true)
    public StorePublicInfoResponse getPublicInfo(String slug) {
        StoreLink link = linkRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        return StorePublicInfoResponse.builder()
                .slug(link.getSlug())
                .shopName(link.getOrganization().getName())
                .pricePaise(pricingService.kioskPricePaise())
                .currency("INR")
                .validDays(link.getValidDays())
                .active(link.isActive())
                .paymentsConfigured(!razorpayKeyId.isBlank() && !razorpayKeySecret.isBlank())
                .build();
    }

    /** URL token like "mehta-paint-house-x7k2p9" — recognizable but unguessable enough. */
    private String generateUniqueSlug(Organization org) {
        String base = org.getSlug() == null ? "shop"
                : org.getSlug().toLowerCase().replaceAll("[^a-z0-9-]", "").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) base = "shop";
        if (base.length() > 40) base = base.substring(0, 40);
        for (int attempts = 0; attempts < 10; attempts++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(SLUG_ALPHABET.charAt(RANDOM.nextInt(SLUG_ALPHABET.length())));
            }
            String slug = base + "-" + suffix;
            if (!linkRepository.existsBySlug(slug)) return slug;
        }
        throw new IllegalStateException("Failed to generate a unique store link");
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean owner = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER);
        boolean manager = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!owner && !manager) {
            throw new SecurityException("Only org owners or managers can manage store links");
        }
    }
}
