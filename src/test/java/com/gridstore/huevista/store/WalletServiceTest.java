package com.gridstore.huevista.store;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.store.dto.RequestRedemptionRequest;
import com.gridstore.huevista.store.dto.WalletRedemptionResponse;
import com.gridstore.huevista.store.dto.WalletSummaryResponse;
import com.gridstore.huevista.store.model.WalletRedemption;
import com.gridstore.huevista.store.model.WalletRedemptionStatus;
import com.gridstore.huevista.store.repository.StorePaymentRepository;
import com.gridstore.huevista.store.repository.WalletRedemptionRepository;
import com.gridstore.huevista.store.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the wallet's derived-balance math and the payout guardrails: a
 * request can never exceed the available balance (PENDING holds funds), the
 * redemption inbox is notified, and only PENDING requests can be decided.
 */
class WalletServiceTest {

    private static final String ORG = "org-1";
    private static final String USER = "user-1";

    private StorePaymentRepository payments;
    private WalletRedemptionRepository redemptions;
    private OrganizationRepository orgs;
    private OrgMembershipRepository memberships;
    private UserRepository users;
    private EmailSender email;
    private AuditService audit;
    private WalletService svc;

    private final Organization org = Organization.builder().id(ORG).name("Mehta Paints").build();

    @BeforeEach
    void setUp() {
        payments = mock(StorePaymentRepository.class);
        redemptions = mock(WalletRedemptionRepository.class);
        orgs = mock(OrganizationRepository.class);
        memberships = mock(OrgMembershipRepository.class);
        users = mock(UserRepository.class);
        email = mock(EmailSender.class);
        audit = mock(AuditService.class);
        svc = new WalletService(payments, redemptions, orgs, memberships, users, email, audit);
        ReflectionTestUtils.setField(svc, "minPricePaise", 5000);
        ReflectionTestUtils.setField(svc, "minRedemptionPaise", 5000);
        ReflectionTestUtils.setField(svc, "redemptionInbox", "redemeamount@huevista.org");
        // The requester owns the org.
        when(memberships.existsByUserIdAndOrganizationIdAndRole(USER, ORG, OrgMemberRole.OWNER)).thenReturn(true);
        when(orgs.findByIdForUpdate(ORG)).thenReturn(Optional.of(org));
        when(users.findById(USER)).thenReturn(Optional.empty());
    }

    private RequestRedemptionRequest request(int amountPaise) {
        RequestRedemptionRequest r = new RequestRedemptionRequest();
        r.setAmountPaise(amountPaise);
        r.setUpiId("mehta@okhdfcbank");
        return r;
    }

    @Test
    void balanceIsEarnedMinusPendingAndRedeemed() {
        when(payments.sumRetailerShareByOrganizationId(ORG)).thenReturn(10_000L);
        when(redemptions.sumByOrganizationIdAndStatus(ORG, WalletRedemptionStatus.PENDING)).thenReturn(2_000L);
        when(redemptions.sumByOrganizationIdAndStatus(ORG, WalletRedemptionStatus.APPROVED)).thenReturn(3_000L);
        when(payments.findTop50ByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of());
        when(redemptions.findByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of());

        WalletSummaryResponse wallet = svc.getWallet(USER, ORG);

        assertThat(wallet.getBalancePaise()).isEqualTo(5_000L);
        assertThat(wallet.getLifetimeEarnedPaise()).isEqualTo(10_000L);
        assertThat(wallet.getPendingRedemptionPaise()).isEqualTo(2_000L);
        assertThat(wallet.getRedeemedPaise()).isEqualTo(3_000L);
    }

    @Test
    void redemptionWithinBalanceIsQueuedAndInboxNotified() {
        when(payments.sumRetailerShareByOrganizationId(ORG)).thenReturn(10_000L);
        when(redemptions.sumHeldByOrganizationId(ORG)).thenReturn(2_000L); // 8_000 available
        when(redemptions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRedemptionResponse res = svc.requestRedemption(USER, ORG, request(8_000));

        assertThat(res.getStatus()).isEqualTo("PENDING");
        assertThat(res.getAmountPaise()).isEqualTo(8_000);
        assertThat(res.getUpiId()).isEqualTo("mehta@okhdfcbank");
        // The manual-payout inbox hears about it.
        verify(email).send(eq("redemeamount@huevista.org"), anyString(), anyString());
        verify(audit).record(eq(USER), eq("WALLET_REDEMPTION_REQUESTED"), eq("WALLET_REDEMPTION"),
                any(), anyString());
    }

    @Test
    void redemptionBeyondAvailableBalanceIsRejected() {
        when(payments.sumRetailerShareByOrganizationId(ORG)).thenReturn(10_000L);
        when(redemptions.sumHeldByOrganizationId(ORG)).thenReturn(2_000L); // 8_000 available

        assertThatThrownBy(() -> svc.requestRedemption(USER, ORG, request(8_001)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("balance");
        verify(redemptions, never()).save(any());
        verify(email, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void redemptionBelowMinimumIsRejected() {
        assertThatThrownBy(() -> svc.requestRedemption(USER, ORG, request(4_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum");
        verify(redemptions, never()).save(any());
    }

    @Test
    void adminDecisionFlipsPendingOnceOnly() {
        WalletRedemption pending = WalletRedemption.builder()
                .id("red-1").organization(org).amountPaise(8_000)
                .upiId("mehta@okhdfcbank").requestedByUserId(USER).build();
        when(redemptions.findByIdForUpdate("red-1")).thenReturn(Optional.of(pending));
        when(redemptions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRedemptionResponse approved = svc.decideRedemption("admin-1", "red-1", true, "paid via UPI");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getAdminNote()).isEqualTo("paid via UPI");
        verify(audit).record(eq("admin-1"), eq("WALLET_REDEMPTION_APPROVED"), eq("WALLET_REDEMPTION"),
                eq("red-1"), anyString());

        // Already decided — the second decision must not double-pay or flip it back.
        assertThatThrownBy(() -> svc.decideRedemption("admin-1", "red-1", false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
    }

    @Test
    void nonMembersCannotTouchTheWallet() {
        assertThatThrownBy(() -> svc.getWallet("stranger", ORG))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> svc.requestRedemption("stranger", ORG, request(5_000)))
                .isInstanceOf(SecurityException.class);
    }
}
