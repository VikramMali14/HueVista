package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Colour-board PDF quota, mirroring how AI previews are billed: a retailer spends
 * their own plan's allowance, while a CUSTOMER account or an anonymous guest spends
 * the allowance of the shop that onboarded them (the entitlement's / access code's
 * organization). Reservation is a single conditional UPDATE, so parallel downloads
 * can't both take the last credit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfQuotaService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final CustomerEntitlementRepository entitlementRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final OrgMembershipRepository orgMembershipRepository;

    @Transactional(readOnly = true)
    public PdfAllowanceResponse allowanceForUser(String userId) {
        return PdfAllowanceResponse.from(billableSubscriptionForUser(userId));
    }

    @Transactional(readOnly = true)
    public PdfAllowanceResponse allowanceForGuest(String accessCodeId) {
        return PdfAllowanceResponse.from(billableSubscriptionForGuest(accessCodeId));
    }

    /** Reserve one download for an account holder; returns the post-charge allowance. */
    @Transactional
    public PdfAllowanceResponse reserveForUser(String userId) {
        return reserve(billableSubscriptionForUser(userId));
    }

    /** Reserve one download for a guest, charged to the issuing shop's plan. */
    @Transactional
    public PdfAllowanceResponse reserveForGuest(String accessCodeId) {
        return reserve(billableSubscriptionForGuest(accessCodeId));
    }

    private PdfAllowanceResponse reserve(Subscription sub) {
        if (subscriptionRepository.incrementPdfUsageIfWithinLimit(sub.getId()) == 0) {
            throw new QuotaExceededException(
                    "Monthly PDF download limit reached (" + sub.getPdfDownloadsLimit() + "). "
                    + "Upgrade the plan or wait for the next billing cycle.");
        }
        // Re-read post-UPDATE so the numbers returned match what was just charged.
        Subscription fresh = subscriptionRepository.findById(sub.getId()).orElse(sub);
        return PdfAllowanceResponse.from(fresh);
    }

    private Subscription billableSubscriptionForUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        // Customers ride on the shop that onboarded them — they have no plan of their own.
        if (user.getRole() == UserRole.CUSTOMER) {
            return entitlementRepository.findByCustomerId(userId)
                    .filter(e -> e.getRetailerOrg() != null)
                    .map(e -> activeSubscriptionForOrgOwner(e.getRetailerOrg().getId()))
                    .orElseThrow(() -> new QuotaExceededException(
                            "PDF downloads are covered by your paint shop's plan — redeem a shop "
                            + "access code first."));
        }
        return subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to download colour-board PDFs."));
    }

    private Subscription billableSubscriptionForGuest(String accessCodeId) {
        CustomerAccessCode code = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));
        return activeSubscriptionForOrgOwner(code.getOrganization().getId());
    }

    private Subscription activeSubscriptionForOrgOwner(String orgId) {
        return orgMembershipRepository.findUserIdsByOrganizationIdAndRole(orgId, OrgMemberRole.OWNER)
                .stream()
                .findFirst()
                .flatMap(ownerId -> subscriptionRepository
                        .findTopByUserIdAndStatusOrderByCreatedAtDesc(ownerId, SubscriptionStatus.ACTIVE))
                .orElseThrow(() -> new QuotaExceededException(
                        "This shop's plan doesn't cover PDF downloads right now — "
                        + "ask the shop, or note the shade names down instead."));
    }
}
