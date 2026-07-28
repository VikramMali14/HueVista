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
 * Retailer-managed public kiosk links. The retailer chooses how long a purchased code
 * lasts and shares/prints the URL; walk-in customers open it, pay the flat platform
 * kiosk price, and land straight in the guest studio.
 *
 * The retailer does NOT price the link. That was removed with the retailer revenue
 * share: the walk-in is HueVista's customer, the whole payment is HueVista's, and the
 * shop earns closed-loop points per sale instead (see {@link StoreKioskService}).
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

    private static final int DEFAULT_VALID_DAYS = 3;
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

        int validDays = request.getValidDays() != null ? request.getValidDays() : DEFAULT_VALID_DAYS;
        StoreLink link = StoreLink.builder()
                .organization(org)
                .slug(generateUniqueSlug(org))
                .validDays(validDays)
                .build();
        link = linkRepository.save(link);

        log.info("Store link created: org={} slug={} validDays={}", orgId, link.getSlug(), validDays);
        return describe(link);
    }

    @Transactional(readOnly = true)
    public List<StoreLinkResponse> listLinks(String requestingUserId, String orgId) {
        requireOwnerOrManager(requestingUserId, orgId);
        return linkRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .map(this::describe)
                .toList();
    }

    @Transactional
    public StoreLinkResponse updateLink(String requestingUserId, String linkId, UpdateStoreLinkRequest request) {
        StoreLink link = linkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Store link not found: " + linkId));
        requireOwnerOrManager(requestingUserId, link.getOrganization().getId());

        if (request.getValidDays() != null) {
            link.setValidDays(request.getValidDays());
        }
        if (request.getActive() != null) {
            link.setActive(request.getActive());
        }
        link = linkRepository.save(link);
        log.info("Store link updated: id={} validDays={} active={}", linkId, link.getValidDays(), link.isActive());
        return describe(link);
    }

    /** A link plus the platform price and what each sale earns the shop. */
    private StoreLinkResponse describe(StoreLink link) {
        return StoreLinkResponse.from(link)
                .withPlatformPricing(pricingService.kioskPricePaise(), pricingService.kioskBonusPointsPaise());
    }

    /** Anonymous kiosk view of a link. 404 when the slug doesn't exist; an inactive
     *  link is still returned (the page explains the kiosk is paused). */
    @Transactional(readOnly = true)
    public StorePublicInfoResponse getPublicInfo(String slug) {
        StoreLink link = linkRepository.findBySlug(slug)
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
