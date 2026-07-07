package com.gridstore.huevista.store.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.store.dto.RequestRedemptionRequest;
import com.gridstore.huevista.store.dto.WalletRedemptionResponse;
import com.gridstore.huevista.store.dto.WalletSummaryResponse;
import com.gridstore.huevista.store.model.WalletRedemption;
import com.gridstore.huevista.store.model.WalletRedemptionStatus;
import com.gridstore.huevista.store.repository.StorePaymentRepository;
import com.gridstore.huevista.store.repository.WalletRedemptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The retailer's kiosk-earnings wallet. There is no balance column anywhere —
 * the balance is always derived (kiosk shares earned minus non-rejected
 * redemptions), so it can never drift from the money records. Payouts are
 * manual: a request emails the redemption inbox with the shop's UPI id, an
 * admin approves (after actually sending the money) or rejects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final StorePaymentRepository paymentRepository;
    private final WalletRedemptionRepository redemptionRepository;
    private final OrganizationRepository orgRepository;
    private final OrgMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final EmailSender emailSender;
    private final AuditService auditService;

    @Value("${app.store.min-price-paise:5000}")
    private int minPricePaise;

    @Value("${app.store.min-redemption-paise:5000}")
    private int minRedemptionPaise;

    @Value("${app.store.redemption-email:redemeamount@huevista.org}")
    private String redemptionInbox;

    @Transactional(readOnly = true)
    public WalletSummaryResponse getWallet(String requestingUserId, String orgId) {
        requireOwnerOrManager(requestingUserId, orgId);
        long earned = paymentRepository.sumRetailerShareByOrganizationId(orgId);
        long pending = redemptionRepository.sumByOrganizationIdAndStatus(orgId, WalletRedemptionStatus.PENDING);
        long redeemed = redemptionRepository.sumByOrganizationIdAndStatus(orgId, WalletRedemptionStatus.APPROVED);

        List<WalletSummaryResponse.PaymentRow> payments = paymentRepository
                .findTop50ByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .map(p -> WalletSummaryResponse.PaymentRow.builder()
                        .id(p.getId())
                        .amountPaise(p.getAmountPaise())
                        .retailerSharePaise(p.getRetailerSharePaise())
                        .code(p.getAccessCode() != null ? p.getAccessCode().getCode() : null)
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();
        List<WalletRedemptionResponse> redemptions = redemptionRepository
                .findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .map(WalletRedemptionResponse::from)
                .toList();

        return WalletSummaryResponse.builder()
                .organizationId(orgId)
                .currency("INR")
                .balancePaise(earned - pending - redeemed)
                .lifetimeEarnedPaise(earned)
                .pendingRedemptionPaise(pending)
                .redeemedPaise(redeemed)
                .platformFeePaise(minPricePaise)
                .recentPayments(payments)
                .redemptions(redemptions)
                .build();
    }

    /**
     * Requests a payout. The org row is pessimistically locked so the balance
     * check and the PENDING insert are atomic — two concurrent requests can't
     * both spend the same rupees. The redemption inbox gets a best-effort email;
     * the request stands even if SMTP is down (the admin queue still shows it).
     */
    @Transactional
    public WalletRedemptionResponse requestRedemption(String requestingUserId, String orgId,
                                                      RequestRedemptionRequest request) {
        requireOwnerOrManager(requestingUserId, orgId);
        Organization org = orgRepository.findByIdForUpdate(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));

        int amount = request.getAmountPaise();
        if (amount < minRedemptionPaise) {
            throw new IllegalArgumentException(
                    "The minimum redemption is Rs." + (minRedemptionPaise / 100));
        }
        long earned = paymentRepository.sumRetailerShareByOrganizationId(orgId);
        long held = redemptionRepository.sumHeldByOrganizationId(orgId);
        long available = earned - held;
        if (amount > available) {
            throw new IllegalStateException(
                    "Your available balance is Rs." + (available / 100.0) + " — you can't redeem more than that.");
        }

        WalletRedemption redemption = redemptionRepository.save(WalletRedemption.builder()
                .organization(org)
                .amountPaise(amount)
                .upiId(request.getUpiId().trim())
                .requestedByUserId(requestingUserId)
                .build());

        auditService.record(requestingUserId, "WALLET_REDEMPTION_REQUESTED", "WALLET_REDEMPTION",
                redemption.getId(), "org=" + orgId + " amountPaise=" + amount);
        notifyInbox(redemption, org, requestingUserId);

        log.info("Wallet redemption requested: org={} amountPaise={} redemption={}",
                orgId, amount, redemption.getId());
        return WalletRedemptionResponse.from(redemption);
    }

    @Transactional(readOnly = true)
    public List<WalletRedemptionResponse> listRedemptions(String requestingUserId, String orgId) {
        requireOwnerOrManager(requestingUserId, orgId);
        return redemptionRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .map(WalletRedemptionResponse::from)
                .toList();
    }

    // ── Admin side ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WalletRedemptionResponse> adminListRedemptions(WalletRedemptionStatus status) {
        List<WalletRedemption> rows = status != null
                ? redemptionRepository.findByStatusWithOrganization(status)
                : redemptionRepository.findAllWithOrganization();
        return rows.stream().map(WalletRedemptionResponse::from).toList();
    }

    /**
     * Admin decision on a PENDING request. Approving records that the admin has
     * (manually) paid the UPI id — the amount leaves the balance for good;
     * rejecting returns it. Either way the requester gets a best-effort email.
     * The row is locked so two admins deciding simultaneously can't both
     * "succeed" — the second sees the already-decided status.
     */
    @Transactional
    public WalletRedemptionResponse decideRedemption(String adminUserId, String redemptionId,
                                                     boolean approve, String note) {
        WalletRedemption redemption = redemptionRepository.findByIdForUpdate(redemptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Redemption not found: " + redemptionId));
        if (redemption.getStatus() != WalletRedemptionStatus.PENDING) {
            throw new IllegalStateException("This redemption was already " + redemption.getStatus().name().toLowerCase() + ".");
        }
        redemption.setStatus(approve ? WalletRedemptionStatus.APPROVED : WalletRedemptionStatus.REJECTED);
        redemption.setDecidedByUserId(adminUserId);
        redemption.setDecidedAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            redemption.setAdminNote(note.trim());
        }
        redemption = redemptionRepository.save(redemption);

        auditService.record(adminUserId,
                approve ? "WALLET_REDEMPTION_APPROVED" : "WALLET_REDEMPTION_REJECTED",
                "WALLET_REDEMPTION", redemptionId,
                "org=" + redemption.getOrganization().getId() + " amountPaise=" + redemption.getAmountPaise());
        notifyRequester(redemption, approve);

        log.info("Wallet redemption {}: id={} org={} amountPaise={}",
                approve ? "approved" : "rejected", redemptionId,
                redemption.getOrganization().getId(), redemption.getAmountPaise());
        return WalletRedemptionResponse.from(redemption);
    }

    private void notifyInbox(WalletRedemption redemption, Organization org, String requestingUserId) {
        try {
            String requesterEmail = userRepository.findById(requestingUserId)
                    .map(User::getEmail).orElse("(unknown)");
            emailSender.send(redemptionInbox,
                    "Wallet redemption request — " + org.getName() + " — Rs." + (redemption.getAmountPaise() / 100.0),
                    "A retailer has requested a wallet payout.\n\n"
                            + "Shop: " + org.getName() + " (org " + org.getId() + ")\n"
                            + "Amount: Rs." + (redemption.getAmountPaise() / 100.0) + "\n"
                            + "UPI id: " + redemption.getUpiId() + "\n"
                            + "Requested by: " + requesterEmail + "\n"
                            + "Redemption id: " + redemption.getId() + "\n\n"
                            + "Review and approve/reject it from the admin console. Approving means you "
                            + "have actually sent the money to the UPI id above.");
        } catch (Exception e) {
            log.warn("Redemption inbox email failed for {}: {}", redemption.getId(), e.getMessage());
        }
    }

    private void notifyRequester(WalletRedemption redemption, boolean approved) {
        try {
            String email = userRepository.findById(redemption.getRequestedByUserId())
                    .map(User::getEmail).orElse(null);
            if (email == null) return;
            String rupees = "Rs." + (redemption.getAmountPaise() / 100.0);
            emailSender.send(email,
                    "Your HueVista payout of " + rupees + (approved ? " is on its way" : " was declined"),
                    approved
                            ? "Your redemption of " + rupees + " has been approved and sent to "
                              + redemption.getUpiId() + ". It should reflect in your account shortly."
                            : "Your redemption of " + rupees + " was declined"
                              + (redemption.getAdminNote() != null ? ": " + redemption.getAdminNote() : ".")
                              + " The amount is back in your wallet balance.");
        } catch (Exception e) {
            log.warn("Redemption decision email failed for {}: {}", redemption.getId(), e.getMessage());
        }
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean owner = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER);
        boolean manager = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!owner && !manager) {
            throw new SecurityException("Only org owners or managers can manage the wallet");
        }
    }
}
