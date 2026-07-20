package com.gridstore.huevista.billing;

import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.model.BillingWallet;
import com.gridstore.huevista.billing.model.BillingWalletTransaction;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.repository.BillingWalletRepository;
import com.gridstore.huevista.billing.repository.BillingWalletTransactionRepository;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.BillingEmailService;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.BillingWalletService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wallet's money-safety core: debits are the conditional UPDATE (insufficient
 * balance means NO transaction row and NO subscription credit), successful spends
 * journal a negative amount and credit the right counter, and top-up bounds hold.
 */
class BillingWalletServiceTest {

    private static final String USER = "user-1";

    private final RazorpayClient razorpay = mock(RazorpayClient.class);
    private final BillingService billing = mock(BillingService.class);
    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final BillingWalletRepository wallets = mock(BillingWalletRepository.class);
    private final BillingWalletTransactionRepository txns = mock(BillingWalletTransactionRepository.class);
    private final ProjectCreditPaymentRepository payments = mock(ProjectCreditPaymentRepository.class);
    private final BillingEmailService emails = mock(BillingEmailService.class);

    private BillingWalletService service() {
        BillingWalletService svc = new BillingWalletService(
                razorpay, billing, subs, wallets, txns, payments, emails);
        ReflectionTestUtils.setField(svc, "keyId", "rzp_key");
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
        ReflectionTestUtils.setField(svc, "minTopUpPaise", 10000L);
        ReflectionTestUtils.setField(svc, "maxTopUpPaise", 10000000L);
        return svc;
    }

    private void walletExists(long balancePaise) {
        when(wallets.findByUserId(USER)).thenReturn(Optional.of(
                BillingWallet.builder().id("w-1").userId(USER).balancePaise(balancePaise).build()));
    }

    @Test
    void payForImageCreditDebitsAndCreditsTheSubscription() {
        walletExists(10000);
        when(wallets.debitIfSufficient(USER, Plan.imageOveragePriceWithTaxInPaise())).thenReturn(1);
        SubscriptionResponse credited = SubscriptionResponse.builder().build();
        when(billing.creditPurchasedImage(USER)).thenReturn(credited);

        SubscriptionResponse out = service().payForImageCredit(USER);

        assertThat(out).isSameAs(credited);
        ArgumentCaptor<BillingWalletTransaction> txn =
                ArgumentCaptor.forClass(BillingWalletTransaction.class);
        verify(txns).save(txn.capture());
        assertThat(txn.getValue().getAmountPaise())
                .isEqualTo(-Plan.imageOveragePriceWithTaxInPaise());
        assertThat(txn.getValue().getType())
                .isEqualTo(BillingWalletTransaction.Type.EXTRA_IMAGE);
    }

    @Test
    void payForAutoMaskCreditDebitsTheGstInclusivePrice() {
        walletExists(10000);
        when(wallets.debitIfSufficient(USER, Plan.autoMaskOveragePriceWithTaxInPaise())).thenReturn(1);
        when(billing.creditPurchasedAutoMask(USER)).thenReturn(SubscriptionResponse.builder().build());

        service().payForAutoMaskCredit(USER);

        // Rs. 25 + 18% GST = 2950 paise.
        verify(wallets).debitIfSufficient(USER, 2950L);
        verify(billing).creditPurchasedAutoMask(USER);
    }

    @Test
    void insufficientBalanceLeavesNoTransactionAndNoCredit() {
        walletExists(1000);
        when(wallets.debitIfSufficient(eq(USER), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service().payForImageCredit(USER))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("wallet balance");
        verify(txns, never()).save(any());
        verify(billing, never()).creditPurchasedImage(any());
    }

    @Test
    void topUpOrderRejectsAmountsOutsideTheConfiguredBounds() {
        when(subs.existsByUserIdAndStatus(eq(USER), any())).thenReturn(true);

        assertThatThrownBy(() -> service().createTopUpOrder(USER, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between");
        assertThatThrownBy(() -> service().createTopUpOrder(USER, 20000000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between");
    }

    @Test
    void topUpOrderRequiresAnActiveSubscription() {
        when(subs.existsByUserIdAndStatus(eq(USER), any())).thenReturn(false);

        assertThatThrownBy(() -> service().createTopUpOrder(USER, 50000))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("No active subscription");
    }
}
