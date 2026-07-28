package com.gridstore.huevista.store;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.service.BillingWalletService;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
    private BillingWalletService billingWallet;
    private PricingService pricing;
    private WalletService svc;

    private final Organization org = Organization.builder().id(ORG).name("Mehta Paints").build();

    @BeforeEach
    void setUp() {
        payments = mock(StorePaymentRepository.class);
        memberships = mock(OrgMembershipRepository.class);
        billingWallet = mock(BillingWalletService.class);
        pricing = mock(PricingService.class);
        svc = new WalletService(payments, memberships, billingWallet, pricing);

        when(memberships.existsByUserIdAndOrganizationIdAndRole(USER, ORG, OrgMemberRole.OWNER)).thenReturn(true);
        when(pricing.shopOwnerUserId(ORG)).thenReturn(Optional.of(OWNER));
        when(pricing.kioskPricePaise()).thenReturn(9_900);
        when(pricing.kioskBonusPointsPaise()).thenReturn(3_900);
    }

    @Test
    void balanceIsTheOwnersSpendableWalletNotASecondLedger() {
        // Lifetime earned and spendable balance differ on purpose: the shop has earned
        // 10,000 points over time and spent some, and the owner also topped up.
        when(payments.sumBonusPointsByOrganizationId(ORG)).thenReturn(10_000L);
        when(billingWallet.balancePaise(OWNER)).thenReturn(7_500L);
        when(payments.findTop50ByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of());

        WalletSummaryResponse wallet = svc.getWallet(USER, ORG);

        assertThat(wallet.getPointsBalancePaise()).isEqualTo(7_500L);
        assertThat(wallet.getLifetimePointsEarnedPaise()).isEqualTo(10_000L);
        assertThat(wallet.getPointsPerSalePaise()).isEqualTo(3_900);
        assertThat(wallet.getKioskPricePaise()).isEqualTo(9_900);
    }

    @Test
    void aShopWithNoOwnerAccountShowsNoSpendableBalance() {
        when(pricing.shopOwnerUserId(ORG)).thenReturn(Optional.empty());
        when(payments.sumBonusPointsByOrganizationId(ORG)).thenReturn(10_000L);
        when(payments.findTop50ByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of());

        assertThat(svc.getWallet(USER, ORG).getPointsBalancePaise()).isZero();
    }

    @Test
    void refundReversesThePaymentAndClawsBackItsPoints() {
        StorePayment payment = StorePayment.builder()
                .id("pay-1").organization(org)
                .paymentId("pay_rzp_1").orderId("order_1")
                .amountPaise(9_900).platformFeePaise(6_000).bonusPointsPaise(3_900)
                .build();
        when(payments.findByPaymentIdForUpdate("pay_rzp_1")).thenReturn(Optional.of(payment));

        svc.reverseKioskPayment("pay_rzp_1", 9_900);

        assertThat(payment.isReversed()).isTrue();
        assertThat(payment.getRefundedPaise()).isEqualTo(9_900);
        verify(billingWallet).reverseKioskBonus(OWNER, 3_900, "pay_rzp_1");
    }

    @Test
    void aSecondRefundWebhookForTheSamePaymentClawsBackNothingMore() {
        StorePayment already = StorePayment.builder()
                .id("pay-1").organization(org)
                .paymentId("pay_rzp_1").orderId("order_1")
                .amountPaise(9_900).bonusPointsPaise(3_900)
                .reversedAt(java.time.LocalDateTime.now())
                .build();
        when(payments.findByPaymentIdForUpdate("pay_rzp_1")).thenReturn(Optional.of(already));

        svc.reverseKioskPayment("pay_rzp_1", 9_900);

        verify(billingWallet, never()).reverseKioskBonus(anyString(), anyLong(), anyString());
    }

    @Test
    void anUnknownPaymentIdIsIgnored() {
        // The same merchant account also takes subscription and top-up payments.
        when(payments.findByPaymentIdForUpdate("pay_not_ours")).thenReturn(Optional.empty());

        svc.reverseKioskPayment("pay_not_ours", 5_000);

        verify(payments, never()).save(any());
        verify(billingWallet, never()).reverseKioskBonus(anyString(), anyLong(), anyString());
    }

    @Test
    void nonMembersCannotReadTheStatement() {
        assertThatThrownBy(() -> svc.getWallet("stranger", ORG))
                .isInstanceOf(SecurityException.class);
        verify(billingWallet, never()).balancePaise(eq(OWNER));
    }
}
