package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the project-credit money path: a payment is credited at most once
 * (replay protection) and only with a valid signature.
 */
class ProjectCreditServiceTest {

    private VerifyProjectCreditRequest req(String order, String payment, String sig) {
        VerifyProjectCreditRequest r = new VerifyProjectCreditRequest();
        r.setOrderId(order);
        r.setPaymentId(payment);
        r.setSignature(sig);
        return r;
    }

    @Test
    void verifiedPaymentCreditsOnce_thenReplayIsRejected() {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        CustomerEntitlementService entitlements = mock(CustomerEntitlementService.class);
        ProjectCreditPaymentRepository payments = mock(ProjectCreditPaymentRepository.class);
        ProjectCreditService svc = new ProjectCreditService(razorpay, entitlements, payments);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);

            // First redemption: payment not seen before -> credits exactly one project.
            when(payments.existsByPaymentId("pay_1")).thenReturn(false);
            svc.verifyAndCredit("user-1", req("order_1", "pay_1", "sig"));
            verify(entitlements, times(1)).creditPurchasedProject("user-1");
            verify(payments, times(1)).saveAndFlush(any());

            // Replay of the same (still-valid) payment: rejected, no extra credit.
            when(payments.existsByPaymentId("pay_1")).thenReturn(true);
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_1", "pay_1", "sig")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been redeemed");
            // creditPurchasedProject still called exactly once across both attempts.
            verify(entitlements, times(1)).creditPurchasedProject("user-1");
        }
    }

    @Test
    void invalidSignatureIsRejectedAndNothingCredited() {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        CustomerEntitlementService entitlements = mock(CustomerEntitlementService.class);
        ProjectCreditPaymentRepository payments = mock(ProjectCreditPaymentRepository.class);
        ProjectCreditService svc = new ProjectCreditService(razorpay, entitlements, payments);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(false);
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_1", "pay_x", "bad")))
                    .isInstanceOf(SecurityException.class);
            verifyNoInteractions(entitlements);
            verify(payments, never()).saveAndFlush(any());
        }
    }
}
