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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final com.gridstore.huevista.billing.repository.SubscriptionPaymentRepository payments =
            mock(com.gridstore.huevista.billing.repository.SubscriptionPaymentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RazorpayClient razorpay = mock(RazorpayClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final com.gridstore.huevista.billing.service.BillingEmailService emails =
            mock(com.gridstore.huevista.billing.service.BillingEmailService.class);

    private BillingService service() {
        BillingService svc = new BillingService(subs, payments, users, razorpay, audit, emails);
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
                .projectsLimit(60).projectsUsed(0)
                .build();
    }

    private static Subscription activePaid(int used, int limit) {
        return activePaid(used, limit, Plan.STARTER);
    }

    private static Subscription activePaid(int used, int limit, Plan plan) {
        User owner = new User();
        owner.setId(USER);
        return Subscription.builder()
                .id(SUB_ID).user(owner).plan(plan)
                .status(SubscriptionStatus.ACTIVE).trial(false)
                .razorpaySubscriptionId("rzp_sub_1")
                .projectsUsed(used).projectsLimit(limit)
                .build();
    }

    // ---- #1 trial can upgrade / cancel ----

    @Test
    void createSubscriptionAllowedWhenOnlyAnActiveTrialExists_andScalesQuotaByQuantity() throws Exception {
        // Only a trial is active -> the "already have a paid plan" guard must NOT trip
        // (the paid-plan lookup filters trials out).
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeTrial()));
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
        // The project quota scales with the billed quantity (x3), and the quantity is
        // stored so renewal can rebuild the allowance without losing the multiplier.
        assertThat(saved.getValue().getProjectsLimit())
                .isEqualTo(Plan.STARTER.getMonthlyProjectLimit() * 3);
        assertThat(saved.getValue().getQuantity()).isEqualTo(3);
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
    void createSubscriptionBlockedForTheSamePlanAlreadyActive() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(0, 25, Plan.STARTER)));
        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        assertThatThrownBy(() -> service().createSubscription(USER, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already on");
        verify(subs, never()).save(any());
    }

    @Test
    void createSubscriptionBlockedForADowngradeWhilePaidPlanActive() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(0, 60, Plan.PROFESSIONAL)));
        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        assertThatThrownBy(() -> service().createSubscription(USER, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel your current one first");
        verify(subs, never()).save(any());
    }

    @Test
    void createSubscriptionAllowedAsAnUpgradeOverAnActivePaidPlan() throws Exception {
        // STARTER is active and paid; buying PROFESSIONAL is a step UP the ladder,
        // so the guard lets it through (activation later supersedes the old plan).
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activePaid(0, 25, Plan.STARTER)));
        User user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.create(any(JSONObject.class)))
                .thenReturn(new com.razorpay.Subscription(new JSONObject().put("id", "rzp_sub_up")));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.PROFESSIONAL);

        SubscriptionResponse out = serviceWithProfessionalPlan().createSubscription(USER, req);

        assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.CREATED);
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subs).save(saved.capture());
        assertThat(saved.getValue().getPlan()).isEqualTo(Plan.PROFESSIONAL);
        assertThat(saved.getValue().getProjectsLimit())
                .isEqualTo(Plan.PROFESSIONAL.getMonthlyProjectLimit());
    }

    private BillingService serviceWithProfessionalPlan() {
        BillingService svc = service();
        ReflectionTestUtils.setField(svc, "planIdProfessional", "plan_professional");
        return svc;
    }

    // ---- checkout attempts: one live payment link, not one per click ----

    @Test
    void aSecondClickOnTheSamePlanReusesThePendingAttemptInsteadOfOpeningAnother() throws Exception {
        Subscription pending = Subscription.builder()
                .id("attempt-1").plan(Plan.STARTER).status(SubscriptionStatus.CREATED)
                .razorpaySubscriptionId("rzp_sub_pending")
                .projectsLimit(Plan.STARTER.getMonthlyProjectLimit())
                .build();
        when(subs.findByUserIdAndStatus(USER, SubscriptionStatus.CREATED))
                .thenReturn(java.util.List.of(pending));
        User user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.fetch("rzp_sub_pending")).thenReturn(new com.razorpay.Subscription(
                new JSONObject().put("id", "rzp_sub_pending").put("status", "created")
                        .put("short_url", "https://rzp.io/i/pending")));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        SubscriptionResponse out = service().createSubscription(USER, req);

        assertThat(out.getRazorpaySubscriptionId()).isEqualTo("rzp_sub_pending");
        assertThat(out.getPaymentUrl()).isEqualTo("https://rzp.io/i/pending");
        verify(razorpay.subscriptions, never()).create(any(JSONObject.class));
        verify(subs, never()).save(any());
    }

    @Test
    void abandonedAttemptsForOtherPlansAreCancelledSoTheirPaymentLinksDie() throws Exception {
        Subscription abandoned = Subscription.builder()
                .id("attempt-old").plan(Plan.BUSINESS).status(SubscriptionStatus.CREATED)
                .razorpaySubscriptionId("rzp_sub_business")
                .projectsLimit(Plan.BUSINESS.getMonthlyProjectLimit())
                .build();
        when(subs.findByUserIdAndStatus(USER, SubscriptionStatus.CREATED))
                .thenReturn(java.util.List.of(abandoned));
        User user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.create(any(JSONObject.class)))
                .thenReturn(new com.razorpay.Subscription(new JSONObject().put("id", "rzp_sub_new")));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        service().createSubscription(USER, req);

        verify(razorpay.subscriptions).cancel(eq("rzp_sub_business"), any(JSONObject.class));
        assertThat(abandoned.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
    }

    // ---- re-subscribing while a paid plan winds down ----

    @Test
    void resubscribingWhileWindingDownSchedulesTheNewPlanForThePeriodEnd() throws Exception {
        Subscription windingDown = activePaid(0, 25, Plan.STARTER);
        windingDown.setCancelAtPeriodEnd(true);
        LocalDateTime endsAt = LocalDateTime.now().plusDays(18);
        windingDown.setCurrentPeriodEnd(endsAt);
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(windingDown));
        User user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.create(any(JSONObject.class)))
                .thenReturn(new com.razorpay.Subscription(new JSONObject().put("id", "rzp_sub_next")));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        service().createSubscription(USER, req);

        // Razorpay is told to start billing the day the current period ends, so the shop
        // is not charged for two overlapping months...
        ArgumentCaptor<JSONObject> sent = ArgumentCaptor.forClass(JSONObject.class);
        verify(razorpay.subscriptions).create(sent.capture());
        assertThat(sent.getValue().getLong("start_at"))
                .isEqualTo(endsAt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
        // ...and the row carries that start date, so findEntitling keeps the OLD plan in
        // force until then rather than handing over the new quota immediately.
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subs).save(saved.capture());
        assertThat(saved.getValue().getCurrentPeriodStart()).isEqualTo(endsAt);
    }

    @Test
    void aTrialDoesNotDeferThePaidPlanItIsReplaced_by() throws Exception {
        Subscription trial = activeTrial();
        trial.setCancelAtPeriodEnd(true);
        trial.setCurrentPeriodEnd(LocalDateTime.now().plusDays(5));
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(trial));
        User user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.create(any(JSONObject.class)))
                .thenReturn(new com.razorpay.Subscription(new JSONObject().put("id", "rzp_sub_now")));

        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlan(Plan.STARTER);

        service().createSubscription(USER, req);

        // Nothing was paid for the trial, so there is no double billing to avoid — making
        // the shop wait it out before the plan they just bought begins would be worse.
        ArgumentCaptor<JSONObject> sent = ArgumentCaptor.forClass(JSONObject.class);
        verify(razorpay.subscriptions).create(sent.capture());
        assertThat(sent.getValue().has("start_at")).isFalse();
    }

    @Test
    void cancellingATrialStopsItRenewingWithoutCallingTheGateway() {
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeTrial()));

        SubscriptionResponse out = service().cancelSubscription(USER);

        // The trial keeps its remaining days — cancelling only stops it renewing (nothing
        // renews a trial anyway), so a retailer who cancels on day 2 of 14 keeps the other 12.
        assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(out.isCancelAtPeriodEnd()).isTrue();
        // A trial has no Razorpay subscription -> the gateway client must be left untouched.
        verifyNoInteractions(razorpay);
    }

    /**
     * A subscription Razorpay has never heard of must not trap the retailer on it.
     *
     * After a key rotation (or a test/live switch) every id stored against the old
     * merchant account comes back "The ID provided is invalid or could not be found".
     * That rethrew as a 500, so "Cancel subscription" failed forever and the row stayed
     * ACTIVE and renewing — with no self-serve way out. There is nothing to cancel at a
     * gateway that does not know the subscription, so the cancellation lands locally.
     */
    @Test
    void cancelStillSucceedsWhenRazorpayDoesNotRecogniseTheSubscription() throws Exception {
        Subscription paid = activePaid(3, 15);
        paid.setCurrentPeriodEnd(LocalDateTime.now().plusDays(20));
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(paid));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.cancel(eq("rzp_sub_1"), any(JSONObject.class)))
                .thenThrow(new com.razorpay.RazorpayException(
                        "BAD_REQUEST_ERROR:The ID provided is invalid or could not be found."));

        SubscriptionResponse out = service().cancelSubscription(USER);

        // Stops renewing, and keeps the days already paid for — same outcome as a
        // cancellation the gateway accepted.
        assertThat(out.isCancelAtPeriodEnd()).isTrue();
        assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subs).save(paid);
        verify(emails).sendCancellationScheduled(paid);
    }

    /**
     * ...but a gateway that is merely unreachable still fails loudly. Recording a
     * cancellation Razorpay never received would tell the customer their card is safe
     * while it goes on being charged; a 5xx they can retry is the honest answer.
     */
    @Test
    void cancelStillFailsWhenTheGatewayIsSimplyUnreachable() throws Exception {
        Subscription paid = activePaid(3, 15);
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(paid));
        razorpay.subscriptions = mock(SubscriptionClient.class);
        when(razorpay.subscriptions.cancel(eq("rzp_sub_1"), any(JSONObject.class)))
                .thenThrow(new com.razorpay.RazorpayException("connect timed out"));

        assertThatThrownBy(() -> service().cancelSubscription(USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment gateway error");
        assertThat(paid.isCancelAtPeriodEnd()).isFalse();
        verify(subs, never()).save(paid);
        verifyNoInteractions(emails);
    }

    // ---- a HALTED plan is still live at the gateway ----

    /**
     * The customer whose payment just failed is the one with the strongest reason to
     * stop the plan, and used to be the only one who could not.
     *
     * cancelSubscription matched on ACTIVE alone, so a HALTED subscription answered "No
     * active subscription found" — while the subscription itself was still live at
     * Razorpay, holding an unpaid invoice against the card and free to resume the moment
     * that invoice settled. The only way out was support.
     */
    @Test
    void aHaltedPlanCanBeCancelledAndIsEndedAtTheGatewayImmediately() throws Exception {
        Subscription halted = activePaid(3, 15);
        halted.setStatus(SubscriptionStatus.HALTED);
        halted.setCurrentPeriodEnd(LocalDateTime.now().plusDays(20));
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(subs.findTopByUserIdAndStatusOrderByCreatedAtDesc(USER, SubscriptionStatus.HALTED))
                .thenReturn(Optional.of(halted));
        razorpay.subscriptions = mock(SubscriptionClient.class);

        SubscriptionResponse out = service().cancelSubscription(USER);

        // Ended outright, not at cycle end: there is no access left to honour, so
        // promising "active till period close" would be a promise of nothing.
        assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        ArgumentCaptor<JSONObject> sent = ArgumentCaptor.forClass(JSONObject.class);
        verify(razorpay.subscriptions).cancel(eq("rzp_sub_1"), sent.capture());
        assertThat(sent.getValue().getInt("cancel_at_cycle_end")).isZero();
        // And the period is closed off, so no lingering end date can re-entitle it.
        assertThat(halted.getCurrentPeriodEnd()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    /**
     * Buying a plan after a failed payment must retire the halted one at the gateway.
     *
     * "Payment fails, shop subscribes again" is the commonest recovery path there is,
     * and supersedeActiveSubscriptions swept only ACTIVE rows — so the halted
     * subscription stayed alive at Razorpay. Settling its outstanding invoice (or a
     * retry landing late) then resurrected it: two entitlements, two monthly charges.
     */
    @Test
    void activatingANewPlanAlsoRetiresAHaltedOne() throws Exception {
        Subscription created = Subscription.builder()
                .id("sub-new").user(activePaid(0, 15).getUser()).plan(Plan.STARTER)
                .status(SubscriptionStatus.CREATED).razorpaySubscriptionId("rzp_sub_new")
                .projectsLimit(15).build();
        Subscription halted = activePaid(3, 15);
        halted.setStatus(SubscriptionStatus.HALTED);

        when(subs.findByRazorpaySubscriptionId("rzp_sub_new")).thenReturn(Optional.of(created));
        when(subs.findByUserIdAndStatus(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(java.util.List.of());
        when(subs.findByUserIdAndStatus(USER, SubscriptionStatus.HALTED))
                .thenReturn(java.util.List.of(halted));
        when(subs.findWithUnspentCredits(eq(USER), any())).thenReturn(java.util.List.of());
        razorpay.subscriptions = mock(SubscriptionClient.class);

        service().activateSubscription("rzp_sub_new", 0, 0);

        assertThat(halted.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        ArgumentCaptor<JSONObject> sent = ArgumentCaptor.forClass(JSONObject.class);
        verify(razorpay.subscriptions).cancel(eq("rzp_sub_1"), sent.capture());
        assertThat(sent.getValue().getInt("cancel_at_cycle_end")).isZero();
    }

    /**
     * A halted plan's unused monthly allowance does NOT follow the shop onto the new
     * plan: it belongs to a cycle the charge for which never went through, so carrying
     * it would hand out a month of quota for a failed payment. (What the shop genuinely
     * owns — bought extras and outstanding code holds — is swept up separately by
     * reclaimStrandedCredits, which reads every row whatever its status.)
     */
    @Test
    void aHaltedPlansUnusedAllowanceIsNotCarriedOntoTheNewPlan() throws Exception {
        Subscription created = Subscription.builder()
                .id("sub-new").user(activePaid(0, 15).getUser()).plan(Plan.STARTER)
                .status(SubscriptionStatus.CREATED).razorpaySubscriptionId("rzp_sub_new")
                .projectsLimit(15).build();
        Subscription halted = activePaid(2, 15);   // 13 unused
        halted.setStatus(SubscriptionStatus.HALTED);

        when(subs.findByRazorpaySubscriptionId("rzp_sub_new")).thenReturn(Optional.of(created));
        when(subs.findByUserIdAndStatus(USER, SubscriptionStatus.ACTIVE))
                .thenReturn(java.util.List.of());
        when(subs.findByUserIdAndStatus(USER, SubscriptionStatus.HALTED))
                .thenReturn(java.util.List.of(halted));
        when(subs.findWithUnspentCredits(eq(USER), any())).thenReturn(java.util.List.of());
        razorpay.subscriptions = mock(SubscriptionClient.class);

        service().activateSubscription("rzp_sub_new", 0, 0);

        verify(subs, never()).addCarriedProjectCredits(eq("sub-new"), anyInt());
    }

    // ---- #2 atomic AI-usage accounting ----

    @Test
    void reserveProjectUsageThrowsWhenNoActiveSubscription() {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of());
        assertThatThrownBy(() -> service().reserveProjectUsage(USER))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void reserveProjectUsageThrowsWhenAtLimit() {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(activePaid(20, 20)));
        // Conditional UPDATE affected no rows -> limit already reached.
        when(subs.incrementProjectUsageIfWithinLimit(SUB_ID)).thenReturn(0);

        assertThatThrownBy(() -> service().reserveProjectUsage(USER))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("used this month's projects");
    }

    @Test
    void reserveProjectUsageSucceedsWhenCreditAvailable() {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(activePaid(5, 20)));
        when(subs.incrementProjectUsageIfWithinLimit(SUB_ID)).thenReturn(1);

        service().reserveProjectUsage(USER);

        verify(subs).incrementProjectUsageIfWithinLimit(SUB_ID);
    }

    @Test
    void incrementProjectUsageChargesViaAtomicUpdate() {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(activePaid(5, 20)));

        service().incrementProjectUsage(USER);

        verify(subs).incrementProjectUsage(eq(SUB_ID));
    }

    @Test
    void refundProjectUsageReturnsCreditViaAtomicUpdate() {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(java.util.List.of(activePaid(6, 20)));

        service().refundProjectUsage(USER);

        verify(subs).decrementProjectUsage(eq(SUB_ID));
    }
}
