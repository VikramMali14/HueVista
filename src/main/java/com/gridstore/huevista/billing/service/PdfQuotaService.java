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
    private final UnbilledAccounts unbilledAccounts;

    @Transactional(readOnly = true)
    public PdfAllowanceResponse allowanceForUser(String userId) {
        if (unbilledAccounts.covers(userId)) {
            return PdfAllowanceResponse.unmetered();
        }
        return PdfAllowanceResponse.from(billableSubscriptionForUser(userId));
    }

    @Transactional(readOnly = true)
    public PdfAllowanceResponse allowanceForGuest(String accessCodeId) {
        CustomerAccessCode code = requireCode(accessCodeId);
        if (code.isSelfFunded()) {
            return selfFundedAllowance(code);
        }
        return PdfAllowanceResponse.from(billableSubscriptionForGuest(accessCodeId));
    }

    /** Reserve one download for an account holder; returns the post-charge allowance. */
    @Transactional
    public PdfAllowanceResponse reserveForUser(String userId) {
        // Nothing to reserve against: an unbilled account has no subscription row, and
        // inventing one to decrement would put a phantom plan in the billing tables.
        if (unbilledAccounts.covers(userId)) {
            return PdfAllowanceResponse.unmetered();
        }
        return reserve(billableSubscriptionForUser(userId));
    }

    /**
     * Reserve one download for a guest.
     *
     * Charged to the issuing shop's plan for a shop-issued code — the shop onboarded that
     * customer and the board is part of the service it is selling them. A SELF-FUNDED
     * (kiosk) code is the other transaction: the walk-in paid for the project and its
     * board at the store link, so it comes out of the code's own allowance and the shop's
     * monthly PDF limit is neither spent nor consulted.
     */
    @Transactional
    public PdfAllowanceResponse reserveForGuest(String accessCodeId) {
        CustomerAccessCode code = requireCode(accessCodeId);
        if (code.isSelfFunded()) {
            if (accessCodeRepository.consumeSelfFundedPdf(accessCodeId) == 0) {
                throw new QuotaExceededException(
                        "You've downloaded the colour board for what you paid for. "
                        + "Buy another visit at the kiosk for more.");
            }
            return selfFundedAllowance(requireCode(accessCodeId));
        }
        return reserve(billableSubscriptionForGuest(accessCodeId));
    }

    /**
     * A self-funded code's own board allowance: one per project the customer paid for.
     *
     * The per-document image cap comes from the FREE plan rather than the shop's, because
     * the shop's plan is deliberately not part of this transaction — reading it here would
     * reintroduce the coupling the self-funded flag exists to remove, and a kiosk board
     * should not get bigger or smaller because the shop changed its own subscription.
     */
    private static PdfAllowanceResponse selfFundedAllowance(CustomerAccessCode code) {
        int limit = Math.max(1, code.getProjectQuota());
        return PdfAllowanceResponse.builder()
                .imagesPerPdf(com.gridstore.huevista.billing.model.Plan.FREE.getPdfImageLimit())
                .monthlyLimit(limit)
                .used(code.getPdfDownloadsUsed())
                .remaining(Math.max(0, limit - code.getPdfDownloadsUsed()))
                .unlimited(false)
                .build();
    }

    private CustomerAccessCode requireCode(String accessCodeId) {
        return accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));
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
                .findEntitling(userId, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
                        java.time.LocalDateTime.now()).stream().findFirst()
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
                        .findEntitling(ownerId, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
                                java.time.LocalDateTime.now()).stream().findFirst())
                .orElseThrow(() -> new QuotaExceededException(
                        "This shop's plan doesn't cover PDF downloads right now — "
                        + "ask the shop, or note the shade names down instead."));
    }
}
