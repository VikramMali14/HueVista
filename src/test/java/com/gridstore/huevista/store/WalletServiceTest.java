package com.gridstore.huevista.store;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.service.RewardPointsService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.store.dto.WalletSummaryResponse;
import com.gridstore.huevista.store.model.StorePayment;
import com.gridstore.huevista.store.repository.StorePaymentRepository;
import com.gridstore.huevista.store.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The kiosk statement after the revenue share was replaced by closed-loop points: the
 * balance shown is the owner's spendable wallet (not a second ledger), a refund claws the
 * points back, and there is no payout path left to guard.
 */
class WalletServiceTest {

    private static final String ORG = "org-1";
    private static final String USER = "user-1";
    private static final String OWNER = "owner-1";

    private StorePaymentRepository payments;
    private OrgMembershipRepository memberships;
    private RewardPointsService points;
    private PricingService pricing;
    private WalletService svc;

    private final Organization org = Organization.builder().id(ORG).name("Mehta Paints").build();

    @BeforeEach
    void setUp() {
        payments = mock(StorePaymentRepository.class);
        memberships = mock(OrgMembershipRepository.class);
        points = mock(RewardPointsService.class);
        pricing = mock(PricingService.class);
        svc = new WalletService(payments, memberships, points, pricing);

        when(memberships.existsByUserIdAndOrganizationIdAndRole(USER, ORG, OrgMemberRole.OWNER)).thenReturn(true);
        when(pricing.shopOwnerUserId(ORG)).thenReturn(Optional.of(OWNER));
        when(pricing.kioskPricePaise()).thenReturn(9_900);
        when(pricing.kioskBonusPoints()).thenReturn(30);
    }

    @Test
    void balanceIsTheOwnersSpendableWalletNotASecondLedger() {
        // Lifetime earned and spendable balance differ on purpose: points expire a year
        // after they are earned, so a lifetime total always runs ahead of what is usable.
        when(payments.sumBonusPointsByOrganizationId(ORG)).thenReturn(300L);
        when(points.balance(OWNER)).thenReturn(120);
        when(payments.findTop50ByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of());

        WalletSummaryResponse wallet = svc.getWallet(USER, ORG);

        assertThat(wallet.getPointsBalance()).isEqualTo(120);
        assertThat(wallet.getLifetimePointsEarned()).isEqualTo(300L);
        assertThat(wallet.getPointsPerSale()).isEqualTo(30);
        assertThat(wallet.getKioskPricePaise()).isEqualTo(9_900);
    }

    @Test
    void aShopWithNoOwnerAccountShowsNoSpendableBalance() {
        when(pricing.shopOwnerUserId(ORG)).thenReturn(Optional.empty());
        when(payments.sumBonusPointsByOrganizationId(ORG)).thenReturn(300L);
        when(payments.findTop50ByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of());

        assertThat(svc.getWallet(USER, ORG).getPointsBalance()).isZero();
    }

    @Test
    void refundReversesThePaymentAndClawsBackItsPoints() {
        StorePayment payment = StorePayment.builder()
                .id("pay-1").organization(org)
                .paymentId("pay_rzp_1").orderId("order_1")
                .amountPaise(9_900).platformFeePaise(9_900).bonusPoints(30)
                .build();
        when(payments.findByPaymentIdForUpdate("pay_rzp_1")).thenReturn(Optional.of(payment));

        svc.reverseKioskPayment("pay_rzp_1", 9_900);

        assertThat(payment.isReversed()).isTrue();
        assertThat(payment.getRefundedPaise()).isEqualTo(9_900);
        verify(points).reverseKioskPoints(OWNER, 30, "pay_rzp_1");
    }

    @Test
    void aSecondRefundWebhookForTheSamePaymentClawsBackNothingMore() {
        StorePayment already = StorePayment.builder()
                .id("pay-1").organization(org)
                .paymentId("pay_rzp_1").orderId("order_1")
                .amountPaise(9_900).bonusPoints(30)
                .reversedAt(java.time.LocalDateTime.now())
                .build();
        when(payments.findByPaymentIdForUpdate("pay_rzp_1")).thenReturn(Optional.of(already));

        svc.reverseKioskPayment("pay_rzp_1", 9_900);

        verify(points, never()).reverseKioskPoints(anyString(), anyInt(), anyString());
    }

    @Test
    void anUnknownPaymentIdIsIgnored() {
        // The same merchant account also takes subscription and top-up payments.
        when(payments.findByPaymentIdForUpdate("pay_not_ours")).thenReturn(Optional.empty());

        svc.reverseKioskPayment("pay_not_ours", 5_000);

        verify(payments, never()).save(any());
        verify(points, never()).reverseKioskPoints(anyString(), anyInt(), anyString());
    }

    @Test
    void nonMembersCannotReadTheStatement() {
        assertThatThrownBy(() -> svc.getWallet("stranger", ORG))
                .isInstanceOf(SecurityException.class);
        verify(points, never()).balance(eq(OWNER));
    }
}
