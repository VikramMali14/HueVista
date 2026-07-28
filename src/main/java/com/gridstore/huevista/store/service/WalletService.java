package com.gridstore.huevista.store.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.service.RewardPointsService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.store.dto.WalletSummaryResponse;
import com.gridstore.huevista.store.repository.StorePaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The shop's kiosk statement: what its link sold and what those sales earned in reward
 * points.
 *
 * <p><b>There is no payout path here, by design.</b> This service used to derive a cash
 * balance from each sale's retailer share and queue manual UPI transfers against it,
 * which made every kiosk payment a collection on the shop's behalf — a regulated pattern
 * that needs Razorpay Route and would not clear an activation review. The kiosk now sells
 * at one flat platform price that is entirely HueVista's, and the shop is rewarded in
 * closed-loop points instead.
 *
 * The points themselves live in the owner's point ledger ({@link RewardPointsService}),
 * spendable on images, auto-masks, projects and reopens at their own point prices, and
 * expiring a year after they are earned. This class only reports: the balance shown here
 * is read from that ledger so the shop sees one number, not a second that can drift.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final StorePaymentRepository paymentRepository;
    private final OrgMembershipRepository membershipRepository;
    private final RewardPointsService rewardPointsService;
    private final PricingService pricingService;

    @Transactional(readOnly = true)
    public WalletSummaryResponse getWallet(String requestingUserId, String orgId) {
        requireOwnerOrManager(requestingUserId, orgId);

        long earned = paymentRepository.sumBonusPointsByOrganizationId(orgId);
        // Spendable balance is the owner's point ledger, not a per-org total: points are
        // earned by the shop but spent by the account that pays for it. Reporting a
        // lifetime sum here instead would show the shop a number it cannot actually spend,
        // since points expire a year after they are earned.
        int balance = pricingService.shopOwnerUserId(orgId)
                .map(rewardPointsService::balance)
                .orElse(0);

        List<WalletSummaryResponse.PaymentRow> payments = paymentRepository
                .findTop50ByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .map(p -> WalletSummaryResponse.PaymentRow.builder()
                        .id(p.getId())
                        .amountPaise(p.getAmountPaise())
                        .bonusPoints(p.getBonusPoints())
                        .reversed(p.isReversed())
                        .code(p.getAccessCode() != null ? p.getAccessCode().getCode() : null)
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();

        return WalletSummaryResponse.builder()
                .organizationId(orgId)
                .currency("INR")
                .pointsBalance(balance)
                .lifetimePointsEarned(earned)
                .pointsPerSale(pricingService.kioskBonusPoints())
                .kioskPricePaise(pricingService.kioskPricePaise())
                .recentPayments(payments)
                .build();
    }

    /**
     * Mark a kiosk payment reversed after Razorpay refunded or charged it back, and take
     * the points it earned back out of the shop's wallet.
     *
     * Called from the webhook path and deliberately tolerant: an unknown payment id is
     * simply not ours (the same merchant account also takes subscription and top-up
     * payments), so it is a no-op rather than an error.
     */
    @Transactional
    public void reverseKioskPayment(String razorpayPaymentId, int refundedPaise) {
        paymentRepository.findByPaymentIdForUpdate(razorpayPaymentId).ifPresent(payment -> {
            if (payment.isReversed()) {
                return; // already handled — refund webhooks retry
            }
            payment.setReversedAt(java.time.LocalDateTime.now());
            payment.setRefundedPaise(Math.max(0, refundedPaise));
            paymentRepository.save(payment);

            String orgId = payment.getOrganization().getId();
            pricingService.shopOwnerUserId(orgId).ifPresentOrElse(
                    ownerUserId -> rewardPointsService.reverseKioskPoints(
                            ownerUserId, payment.getBonusPoints(), razorpayPaymentId),
                    () -> log.warn("Refunded kiosk payment {} for org {} has no owner account — "
                            + "no points to take back.", razorpayPaymentId, orgId));

            log.warn("Kiosk payment reversed: payment={} org={} pointsClawedBack={}",
                    razorpayPaymentId, orgId, payment.getBonusPoints());
        });
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean owner = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER);
        boolean manager = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!owner && !manager) {
            throw new SecurityException("Only org owners or managers can manage the wallet");
        }
    }
}
