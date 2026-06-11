package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

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
 * (replay protection), only with a valid signature, and only when the paid
 * order is the project-credit order we created for that user.
 */
class ProjectCreditServiceTest {

    private static final int AMOUNT_PAISE = 4900;

    private VerifyProjectCreditRequest req(String order, String payment, String sig) {
        VerifyProjectCreditRequest r = new VerifyProjectCreditRequest();
        r.setOrderId(order);
        r.setPaymentId(payment);
        r.setSignature(sig);
        return r;
    }

    private static Order order(int amount, String purpose, String userId) {
        JSONObject json = new JSONObject();
        json.put("amount", amount);
        JSONObject notes = new JSONObject();
        notes.put("purpose", purpose);
        notes.put("userId", userId);
        json.put("notes", notes);
        return new Order(json);
    }

    private static ProjectCreditService service(RazorpayClient razorpay,
                                                CustomerEntitlementService entitlements,
                                                ProjectCreditPaymentRepository payments) {
        ProjectCreditService svc = new ProjectCreditService(razorpay, entitlements, payments);
        ReflectionTestUtils.setField(svc, "amountPaise", AMOUNT_PAISE);
        return svc;
    }

    @Test
    void verifiedPaymentCreditsOnce_thenReplayIsRejected() throws Exception {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        when(razorpay.orders.fetch("order_1"))
                .thenReturn(order(AMOUNT_PAISE, "project_credit", "user-1"));
        CustomerEntitlementService entitlements = mock(CustomerEntitlementService.class);
        ProjectCreditPaymentRepository payments = mock(ProjectCreditPaymentRepository.class);
        ProjectCreditService svc = service(razorpay, entitlements, payments);

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
        ProjectCreditService svc = service(razorpay, entitlements, payments);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(false);
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_1", "pay_x", "bad")))
                    .isInstanceOf(SecurityException.class);
            verifyNoInteractions(entitlements);
            verify(payments, never()).saveAndFlush(any());
        }
    }

    @Test
    void paymentForDifferentOrderIsRejected() throws Exception {
        RazorpayClient razorpay = mock(RazorpayClient.class);
        razorpay.orders = mock(OrderClient.class);
        CustomerEntitlementService entitlements = mock(CustomerEntitlementService.class);
        ProjectCreditPaymentRepository payments = mock(ProjectCreditPaymentRepository.class);
        ProjectCreditService svc = service(razorpay, entitlements, payments);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyPaymentSignature(any(JSONObject.class), any()))
                    .thenReturn(true);

            // Wrong amount (a cheaper order paid on the same merchant account).
            when(razorpay.orders.fetch("order_cheap")).thenReturn(order(100, "project_credit", "user-1"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_cheap", "pay_a", "sig")))
                    .isInstanceOf(SecurityException.class);

            // Wrong purpose (an order created for something else entirely).
            when(razorpay.orders.fetch("order_other")).thenReturn(order(AMOUNT_PAISE, "subscription", "user-1"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_other", "pay_b", "sig")))
                    .isInstanceOf(SecurityException.class);

            // Order created for a different user.
            when(razorpay.orders.fetch("order_theirs")).thenReturn(order(AMOUNT_PAISE, "project_credit", "user-2"));
            assertThatThrownBy(() -> svc.verifyAndCredit("user-1", req("order_theirs", "pay_c", "sig")))
                    .isInstanceOf(SecurityException.class);

            verifyNoInteractions(entitlements);
            verify(payments, never()).saveAndFlush(any());
        }
    }
}
