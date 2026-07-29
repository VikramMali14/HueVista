package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionPayment;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionPaymentRepository;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.common.audit.AuditService;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        return service(subs, mock(SubscriptionPaymentRepository.class));
    }

    private static BillingService service(SubscriptionRepository subs, SubscriptionPaymentRepository payments) {
        BillingService svc = new BillingService(subs, payments, mock(UserRepository.class),
                mock(RazorpayClient.class), mock(AuditService.class),
                mock(com.gridstore.huevista.billing.service.BillingEmailService.class));
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

    /**
     * The signature is a plain HMAC over "<payment>|<subscription>" — no nonce, no expiry
     * — so the payload from a shop's first successful checkout stays valid forever. It
     * must not buy a second month once the plan has ended.
     */
    @Test
    void anEndedSubscriptionCannotBeRevivedByReplayingItsOriginalPayload() {
        for (SubscriptionStatus ended : new SubscriptionStatus[]{
                SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED}) {
            SubscriptionRepository subs = mock(SubscriptionRepository.class);
            when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                    .thenReturn(Optional.of(sub("user-1", ended)));
            BillingService svc = service(subs);

            try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
                utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

                assertThatThrownBy(() -> svc.verifyAndActivateSubscription(
                        "user-1", req("rzp_sub_1", "pay_1", "sig")))
                        .as("%s must not be re-activated", ended)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("already ended");
                verify(subs, never()).save(any());
            }
        }
    }

    /** One payment authorizes one activation, even across two live subscriptions. */
    @Test
    void aPaymentAlreadyClaimedCannotActivateAgain() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                .thenReturn(Optional.of(sub("user-1", SubscriptionStatus.CREATED)));
        SubscriptionPaymentRepository payments = mock(SubscriptionPaymentRepository.class);
        when(payments.existsById("pay_1")).thenReturn(true);
        BillingService svc = service(subs, payments);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> svc.verifyAndActivateSubscription(
                    "user-1", req("rzp_sub_1", "pay_1", "sig")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been used");
            verify(subs, never()).save(any());
        }
    }

    /** A first activation claims its payment id, so the next replay has something to hit. */
    @Test
    void activationClaimsThePaymentId() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                .thenReturn(Optional.of(sub("user-1", SubscriptionStatus.CREATED)));
        SubscriptionPaymentRepository payments = mock(SubscriptionPaymentRepository.class);
        BillingService svc = service(subs, payments);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            svc.verifyAndActivateSubscription("user-1", req("rzp_sub_1", "pay_1", "sig"));

            ArgumentCaptor<SubscriptionPayment> claimed = ArgumentCaptor.forClass(SubscriptionPayment.class);
            verify(payments).saveAndFlush(claimed.capture());
            assertThat(claimed.getValue().getPaymentId()).isEqualTo("pay_1");
            assertThat(claimed.getValue().getRazorpaySubscriptionId()).isEqualTo("rzp_sub_1");
            assertThat(claimed.getValue().getUserId()).isEqualTo("user-1");
        }
    }

    /**
     * A plan bought to replace one still winding down is scheduled at the gateway for the
     * day that period ends. Authorizing it must leave the start date alone — dragging it
     * forward to "now" is what handed the buyer the new quota a month early and expired
     * the plan they had already paid for.
     */
    @Test
    void authorizingAScheduledPlanKeepsItsStartDateAndSupersedesNothing() {
        Subscription scheduled = sub("user-1", SubscriptionStatus.CREATED);
        LocalDateTime startsAt = LocalDateTime.now().plusDays(20);
        scheduled.setCurrentPeriodStart(startsAt);
        scheduled.setCurrentPeriodEnd(startsAt.plusMonths(1));

        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1")).thenReturn(Optional.of(scheduled));
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            SubscriptionResponse out = svc.verifyAndActivateSubscription(
                    "user-1", req("rzp_sub_1", "pay_1", "sig"));

            assertThat(out.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(out.getCurrentPeriodStart()).isEqualTo(startsAt);
            // The plan it replaces keeps running to its own period end.
            verify(subs, never()).findByUserIdAndStatus(any(), eq(SubscriptionStatus.ACTIVE));
        }
    }

    /**
     * Upgrading must not destroy what the shop already paid for. Purchased credits are
     * money; the image holds are projects paid for when access codes were issued, and
     * losing them billed the shop a SECOND time when the customer finally redeemed one
     * (SegmentationService finds no hold and falls through to a normal charge).
     */
    @Test
    void upgradingCarriesPurchasedCreditsAndAccessCodeHoldsOntoTheNewPlan() {
        Subscription old = sub("user-1", SubscriptionStatus.ACTIVE);
        old.setId("sub-row-old");
        old.setRazorpaySubscriptionId("rzp_sub_old");
        old.setPlan(Plan.STARTER);
        old.setPurchasedImageCredits(12);
        old.setPurchasedAutoMaskCredits(3);
        old.setReservedImages(4);
        old.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));

        Subscription bought = sub("user-1", SubscriptionStatus.CREATED);
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1")).thenReturn(Optional.of(bought));
        when(subs.findByUserIdAndStatus("user-1", SubscriptionStatus.ACTIVE))
                .thenReturn(java.util.List.of(old));
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            svc.verifyAndActivateSubscription("user-1", req("rzp_sub_1", "pay_1", "sig"));

            verify(subs).addPurchasedImageCredits("sub-row-1", 12);
            verify(subs).addPurchasedAutoMaskCredits("sub-row-1", 3);
            verify(subs).addReservedImages("sub-row-1", 4);
            // Moved, not copied — the retired row must not still count them.
            assertThat(old.getPurchasedImageCredits()).isZero();
            assertThat(old.getPurchasedAutoMaskCredits()).isZero();
            assertThat(old.getReservedImages()).isZero();
        }
    }

    /**
     * Retiring a plan closes its period too. A superseded row left holding next month's
     * end date came back as an entitling CANCELLED one as soon as Razorpay echoed the
     * cancellation — a free second plan alongside the one that replaced it.
     */
    @Test
    void aSupersededPlansPaidPeriodIsClosedSoItCannotEntitleAgain() {
        Subscription old = sub("user-1", SubscriptionStatus.ACTIVE);
        old.setId("sub-row-old");
        old.setRazorpaySubscriptionId("rzp_sub_old");
        old.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));

        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1"))
                .thenReturn(Optional.of(sub("user-1", SubscriptionStatus.CREATED)));
        when(subs.findByUserIdAndStatus("user-1", SubscriptionStatus.ACTIVE))
                .thenReturn(java.util.List.of(old));
        BillingService svc = service(subs);

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(any(), any(), any())).thenReturn(true);

            svc.verifyAndActivateSubscription("user-1", req("rzp_sub_1", "pay_1", "sig"));

            assertThat(old.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
            assertThat(old.getCurrentPeriodEnd()).isBeforeOrEqualTo(LocalDateTime.now());
        }
    }

    /** ...and the cancellation echo for that retired row must leave it retired. */
    @Test
    void aCancellationWebhookDoesNotReviveAnAlreadyRetiredSubscription() {
        Subscription retired = sub("user-1", SubscriptionStatus.EXPIRED);
        retired.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByRazorpaySubscriptionId("rzp_sub_1")).thenReturn(Optional.of(retired));

        service(subs).markCancelled("rzp_sub_1");

        assertThat(retired.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        verify(subs, never()).save(any());
    }
}
