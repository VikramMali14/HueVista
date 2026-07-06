package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.razorpay.RazorpayClient;
import com.razorpay.SubscriptionClient;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the subscription-lifecycle fixes: a trialing retailer can upgrade to a paid
 * plan, cancelling a trial doesn't hit the payment gateway, the billed quantity scales
 * the AI quota, and AI usage is reserved/charged/refunded through the atomic repository
 * queries (no read-modify-write).
 */
class BillingServiceLifecycleTest {

    private static final String USER = "user-1";
    private static final String SUB_ID = "sub-row-1";

    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RazorpayClient razorpay = mock(RazorpayClient.class);
    private final AuditService audit = mock(AuditService.class);

    private BillingService service() {
        BillingService svc = new BillingService(subs, users, razorpay, audit);
        ReflectionTestUtils.setField(svc, "keyId", "rzp_key");
        ReflectionTestUtils.setField(svc, "keySecret", "secret");
        ReflectionTestUtils.setField(svc, "planIdStarter", "plan_starter");
        ReflectionTestUtils.setField(svc, "subscriptionTotalCount", 120);
        return svc;
    }

    private static Subscription activeTrial() {
        User owner = new User();
        owner.setId(USER);
        return Subscription.builder()
                .id(SUB_ID).user(owner).plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE).trial(true)
                .aiGenerationsLimit(60).aiGenerationsUsed(0)
                .build();
    }

    private static Subscription activePaid(int used, int limit) {
        User owner = new User();
        owner.setId(USER);
        return Subscription.builder()
                .id(SUB_ID).user(owner).plan(Plan.STARTER)
                .status(SubscriptionStatus.ACTIVE).trial(false)
                .razorpaySubscriptionId("rzp_sub_1")
                .aiGenerationsUsed(used).aiGenerationsLimit(limit)
                .build();
    }

    // ---- #1 trial can upgrade / cancel ----

    @Test
    void createSubscriptionAllowedWhenOnlyAnActiveTrialExists_andScalesQuotaByQuantity() throws Exception {
        // Only a trial is active -> the "already have a paid plan" guard must NOT trip.
        when(subs.existsByUserIdAndStatusAndTrialFalse(USER, SubscriptionStatus.ACTIVE)).thenReturn(false);
        User user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.create(any(JSONObject.class)))
                .thenReturn(new com.razorpay.Subscription(new JSONObject().put("id", "rzp_sub_new")));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);
        req.setQuantity(3);

        SubscriptionResponse out = service().createSubscription(USER, req);

        assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.CREATED);
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subs).save(saved.capture());
        // STARTER grants 20/month; billed quantity 3 => 60.
        assertThat(saved.getValue().getAiGenerationsLimit()).isEqualTo(60);
    }

    @Test
    void customersCannotBuyRetailerPlans() {
        // A CUSTOMER paying for a plan would get nothing (their access is governed by
        // the retailer's access-code entitlement) — the charge must be refused.
        when(subs.existsByUserIdAndStatusAndTrialFalse(USER, SubscriptionStatus.ACTIVE)).thenReturn(false);
        User customer = new User();
        customer.setId(USER);
        customer.setRole(com.gridstore.huevista.auth.model.UserRole.CUSTOMER);
        when(users.findById(USER)).thenReturn(Optional.of(customer));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        assertThatThrownBy(() -> service().createSubscription(USER, req))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("paint shops");
        verify(subs, never()).save(any());
    }

    @Test
    void createSubscriptionBlockedByAnActivePaidPlan() {
        when(subs.existsByUserIdAndStatusAndTrialFalse(USER, SubscriptionStatus.ACTIVE)).thenReturn(true);
        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        assertThatThrownBy(() -> service().createSubscription(USER, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already have an active subscription");
        verify(subs, never()).save(any());
    }

    @Test
    void cancellingATrialEndsItLocallyWithoutCallingTheGateway() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeTrial()));

        SubscriptionResponse out = service().cancelSubscription(USER);

        assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        // A trial has no Razorpay subscription -> the gateway client must be left untouched.
        verifyNoInteractions(razorpay);
    }

    // ---- #2 atomic AI-usage accounting ----

    @Test
    void reserveAiUsageThrowsWhenNoActiveSubscription() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().reserveAiUsage(USER))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void reserveAiUsageThrowsWhenAtLimit() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(20, 20)));
        // Conditional UPDATE affected no rows -> limit already reached.
        when(subs.incrementAiUsageIfWithinLimit(SUB_ID)).thenReturn(0);

        assertThatThrownBy(() -> service().reserveAiUsage(USER))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("limit reached");
    }

    @Test
    void reserveAiUsageSucceedsWhenCreditAvailable() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(5, 20)));
        when(subs.incrementAiUsageIfWithinLimit(SUB_ID)).thenReturn(1);

        service().reserveAiUsage(USER);

        verify(subs).incrementAiUsageIfWithinLimit(SUB_ID);
    }

    @Test
    void incrementAiUsageChargesViaAtomicUpdate() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(5, 20)));

        service().incrementAiUsage(USER);

        verify(subs).incrementAiUsage(eq(SUB_ID));
    }

    @Test
    void refundAiUsageReturnsCreditViaAtomicUpdate() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(6, 20)));

        service().refundAiUsage(USER);

        verify(subs).decrementAiUsage(eq(SUB_ID));
    }
}
