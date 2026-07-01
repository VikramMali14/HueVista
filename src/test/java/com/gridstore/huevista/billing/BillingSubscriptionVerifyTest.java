package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.common.audit.AuditService;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the synchronous "activate on return from Checkout" path: a valid signature
 * flips the subscription to ACTIVE, a forged signature or a subscription owned by
 * someone else is rejected, and re-verifying an already-active plan is a no-op.
 */
class BillingSubscriptionVerifyTest {

    private static VerifySubscriptionRequest req(String subId, String paymentId, String sig) {
        VerifySubscriptionRequest r = new VerifySubscriptionRequest();
        r.setSubscriptionId(subId);
        r.setPaymentId(paymentId);
        r.setSignature(sig);
        return r;
    }

    private static Subscription sub(String ownerId, SubscriptionStatus status) {
        User owner = new User();
        owner.setId(ownerId);
        return Subscription.builder()
                .id("sub-row-1")
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(status)
                .razorpaySubscriptionId("rzp_sub_1")
                .aiGenerationsLimit(60)
                .build();
    }

    private static BillingService service(SubscriptionRepository subs) {
        BillingService svc = new BillingService(subs, mock(UserRepository.class),
                mock(RazorpayClient.class), mock(AuditService.class));
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
        return svc;
    }

    @Test
    void validSignatureActivatesSubscription() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                .thenReturn(Optional.of(sub("user-1", SubscriptionStatus.CREATED)));
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            SubscriptionResponse out = svc.verifyAndActivateSubscription(
                    "user-1", req("rzp_sub_1", "pay_1", "sig"));

            assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(out.isTrial()).isFalse();
            verify(subs, times(1)).save(any(Subscription.class));
        }
    }

    @Test
    void forgedSignatureIsRejected() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> svc.verifyAndActivateSubscription(
                    "user-1", req("rzp_sub_1", "pay_1", "bad")))
                    .isInstanceOf(SecurityException.class);
            verify(subs, never()).save(any());
        }
    }

    @Test
    void subscriptionOwnedByAnotherUserIsRejected() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                .thenReturn(Optional.of(sub("user-2", SubscriptionStatus.CREATED)));
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndActivateSubscription(
                    "user-1", req("rzp_sub_1", "pay_1", "sig")))
                    .isInstanceOf(SecurityException.class);
            verify(subs, never()).save(any());
        }
    }

    @Test
    void reVerifyingAnActiveSubscriptionIsANoOp() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                .thenReturn(Optional.of(sub("user-1", SubscriptionStatus.ACTIVE)));
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            SubscriptionResponse out = svc.verifyAndActivateSubscription(
                    "user-1", req("rzp_sub_1", "pay_1", "sig"));

            assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            verify(subs, never()).save(any()); // already active: no write
        }
    }
}
