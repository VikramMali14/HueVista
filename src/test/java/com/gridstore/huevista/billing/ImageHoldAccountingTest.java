package com.gridstore.huevista.billing;

import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.ImageLimitReachedException;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.billing.service.BillingService;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The image-credit HOLD accounting introduced so a shop is billed exactly once per
 * assigned project.
 *
 * A retailer access code with quota N holds N image credits when it is generated; each
 * credit is SPENT when that project is actually rendered, and RETURNED if the code is
 * revoked or expires unredeemed. Before this, generation charged N images outright and
 * the render charged again — the shop paid twice — while an unredeemed code kept the
 * quota forever.
 */
class ImageHoldAccountingTest {

    private static final String USER = "user-1";
    private static final String SUB_ID = "sub-1";

    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final BillingService service = new BillingService(
            subs,
            mock(com.gridstore.huevista.auth.repository.UserRepository.class),
            mock(RazorpayClient.class),
            mock(com.gridstore.huevista.common.audit.AuditService.class),
            mock(com.gridstore.huevista.billing.service.BillingEmailService.class));

    private Subscription sub(int used, int held, int limit) {
        return Subscription.builder()
                .id(SUB_ID)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .aiGenerationsUsed(used)
                .reservedImages(held)
                .aiGenerationsLimit(limit)
                .currentPeriodEnd(LocalDateTime.now().plusDays(20))
                .build();
    }

    private void entitling(Subscription s) {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(s == null ? List.of() : List.of(s));
    }

    @Test
    void heldCreditsCountAgainstTheAllowance() {
        // 10-image plan, 2 spent and 8 held by outstanding codes: nothing left to give out.
        entitling(sub(2, 8, 10));

        assertThatThrownBy(() -> service.assertAiQuotaAvailable(USER))
                .isInstanceOf(ImageLimitReachedException.class);
    }

    @Test
    void aRunCoveredByAHeldCreditIsAllowedEvenAtTheCeiling() {
        // Same exhausted plan, but this run already owns one of those holds — the shop
        // paid for it at code-generation time, so re-checking the limit would block work
        // that has in fact been bought.
        entitling(sub(2, 8, 10));

        service.assertAiQuotaAvailable(USER, true);
    }

    @Test
    void spendingAHoldMovesItIntoUsageRatherThanChargingAgain() {
        entitling(sub(0, 3, 10));
        when(subs.consumeReservedImage(SUB_ID)).thenReturn(1);

        assertThat(service.consumeReservedImage(USER)).isTrue();
    }

    @Test
    void aCodeWithNoHoldLeftFallsBackToANormalCharge() {
        // Legacy codes (issued before holds existed) hold nothing; the caller must then
        // charge normally rather than silently doing the work for free.
        entitling(sub(0, 0, 10));
        when(subs.consumeReservedImage(SUB_ID)).thenReturn(0);

        assertThat(service.consumeReservedImage(USER)).isFalse();
    }

    @Test
    void revokingACodeReturnsItsHeldCreditsToTheShop() {
        entitling(sub(0, 3, 10));

        service.releaseReservedImages(USER, 3);

        org.mockito.Mockito.verify(subs).releaseReservedImages(SUB_ID, 3);
    }

    @Test
    void releasingNothingIsANoOp() {
        service.releaseReservedImages(USER, 0);
        org.mockito.Mockito.verifyNoInteractions(subs);
    }

    @Test
    void aCancelledPlanStillEntitlesUntilItsPaidPeriodEnds() {
        // The gate matched on ACTIVE only, so the moment Razorpay echoed
        // subscription.cancelled every feature 402'd while the account page still said
        // "cancelled · active till period end".
        Subscription windingDown = sub(0, 0, 10);
        windingDown.setStatus(SubscriptionStatus.CANCELLED);
        windingDown.setCancelAtPeriodEnd(true);
        entitling(windingDown);

        service.assertAiQuotaAvailable(USER);
    }

    @Test
    void noEntitlingSubscriptionStillRefuses() {
        entitling(null);

        assertThatThrownBy(() -> service.assertAiQuotaAvailable(USER))
                .isInstanceOf(QuotaExceededException.class);
    }
}
