package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.FreeTierService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The free tier's cycle: it renews, it is what a lapsed shop lands back on, and neither
 * of those quietly costs the shop something it paid for.
 *
 * These are the guarantees that distinguish the free TIER from the seven-day trial it
 * replaced — the trial ran out and stayed run out, so nothing here had to be true of it.
 */
class FreeTierServiceTest {

    private static final String USER = "user-1";

    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FreeTierService service = new FreeTierService(subs, users);

    private User retailer() {
        return User.builder().id(USER).email("shop@example.com").name("Shop")
                .role(UserRole.RETAILER).build();
    }

    private Subscription lapsedFreeCycle() {
        LocalDateTime start = LocalDateTime.now().minusMonths(1).minusDays(2);
        return Subscription.builder()
                .id("sub-1")
                .user(retailer())
                .plan(Plan.FREE)
                .status(SubscriptionStatus.ACTIVE)
                .trial(false)
                .currentPeriodStart(start)
                .currentPeriodEnd(start.plusMonths(1))
                .projectsUsed(2)
                .projectsLimit(2)
                .pdfDownloadsUsed(5)
                .pdfDownloadsLimit(5)
                .build();
    }

    /** Nothing entitles this user — the state a shop is in after a plan lapses. */
    private void nothingEntitling() {
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED), any())).thenReturn(List.of());
    }

    /** JPA generates the id during the write, and the credit sweep keys on it — so the
     *  fake has to hand one back the way a real save does. */
    private void savesAssignIds() {
        when(subs.save(any())).thenAnswer(call -> {
            Subscription s = call.getArgument(0);
            if (s.getId() == null) s.setId("free-row-1");
            return s;
        });
    }

    private void dueFreeCycles(List<Subscription> due) {
        when(subs.findDueFreeCycles(eq(USER), eq(Plan.FREE), eq(SubscriptionStatus.ACTIVE), any()))
                .thenReturn(due);
    }

    @Test
    void a_spent_free_month_rolls_over_with_the_allowance_back() {
        Subscription sub = lapsedFreeCycle();
        service.renew(sub);

        assertThat(sub.getProjectsUsed()).isZero();
        assertThat(sub.getPdfDownloadsUsed()).isZero();
        assertThat(sub.getProjectsLimit()).isEqualTo(2);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        // A month from NOW, not from the period that just ended: an account dormant for
        // six months should come back to a live cycle, not need six rolls to catch up.
        assertThat(sub.getCurrentPeriodEnd())
                .isEqualTo(sub.getCurrentPeriodStart().plusMonths(1))
                .isAfter(LocalDateTime.now());
    }

    /** The renewal is the counterpart of the trial's dead end, so it must not be free to
     *  call twice — a second roll inside the same month would double the allowance. */
    @Test
    void a_cycle_that_has_not_elapsed_is_left_alone() {
        Subscription sub = lapsedFreeCycle();
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(10));
        sub.setProjectsUsed(2);

        service.renew(sub);

        assertThat(sub.getProjectsUsed()).isEqualTo(2);
        verify(subs, never()).save(any());
    }

    /** Paid-for credits and outstanding holds outlive the cycle, exactly as on a paid
     *  renewal: one is money, the other is spoken for by a code already in a customer's
     *  hands. Carried-over credits do not — they were a month's allowance, lent once. */
    @Test
    void renewal_keeps_what_the_shop_owns_and_drops_what_was_only_lent() {
        Subscription sub = lapsedFreeCycle();
        sub.setPurchasedProjectCredits(3);
        sub.setReservedProjects(1);
        sub.setCarriedProjectCredits(4);

        service.renew(sub);

        assertThat(sub.getPurchasedProjectCredits()).isEqualTo(3);
        assertThat(sub.getReservedProjects()).isEqualTo(1);
        assertThat(sub.getCarriedProjectCredits()).isZero();
    }

    /** The seven-day trial's monotonic counter never reset by design. Carrying it into a
     *  renewing tier would cap every free shop at two projects for the life of the
     *  account, whatever the calendar said. */
    @Test
    void renewal_clears_the_old_trial_counter() {
        Subscription sub = lapsedFreeCycle();
        sub.setTrialProjectsCreated(2);

        service.renew(sub);

        assertThat(sub.getTrialProjectsCreated()).isZero();
    }

    @Test
    void a_retailer_left_with_no_plan_lands_back_on_the_free_tier() {
        dueFreeCycles(List.of());
        nothingEntitling();
        savesAssignIds();
        when(users.findById(USER)).thenReturn(Optional.of(retailer()));
        when(subs.findWithUnspentCredits(eq(USER), anyString())).thenReturn(List.of());

        service.ensureCurrentCycle(USER);

        org.mockito.ArgumentCaptor<Subscription> saved =
                org.mockito.ArgumentCaptor.forClass(Subscription.class);
        verify(subs).save(saved.capture());
        Subscription created = saved.getValue();
        assertThat(created.getPlan()).isEqualTo(Plan.FREE);
        assertThat(created.isTrial()).isFalse();
        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(created.getProjectsLimit()).isEqualTo(2);
    }

    /**
     * A shop still inside a plan it paid for is left alone.
     *
     * The entitlement lookup prefers the newest ACTIVE row, so minting a 2-project free
     * row for a shop midway through a Business month would silently downgrade a paying
     * customer to the free tier's allowance.
     */
    @Test
    void a_shop_with_a_live_plan_is_not_given_a_free_row() {
        dueFreeCycles(List.of());
        when(subs.findEntitling(eq(USER), eq(SubscriptionStatus.ACTIVE),
                eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(List.of(Subscription.builder()
                        .id("paid").user(retailer()).plan(Plan.BUSINESS)
                        .status(SubscriptionStatus.ACTIVE).projectsLimit(100).build()));

        service.ensureCurrentCycle(USER);

        verify(subs, never()).save(any());
    }

    /** Plans are shop products. A customer works through the code their shop gave them,
     *  so minting a subscription for one would invent an entitlement nothing reads. */
    @Test
    void a_customer_is_never_given_a_free_tier_row() {
        dueFreeCycles(List.of());
        nothingEntitling();
        when(users.findById(USER)).thenReturn(Optional.of(User.builder()
                .id(USER).email("c@example.com").name("Cust").role(UserRole.CUSTOMER).build()));

        service.ensureCurrentCycle(USER);

        verify(subs, never()).save(any());
    }

    /**
     * Landing on the free tier sweeps up what the dead plan was still holding.
     *
     * Without it a shop that bought three extra projects and let its plan lapse would find
     * them on a row nothing can read, and a customer redeeming an outstanding access code
     * would be charged for a project the shop had already paid for.
     */
    @Test
    void restoring_the_free_tier_carries_paid_credits_and_holds_across() {
        dueFreeCycles(List.of());
        nothingEntitling();
        savesAssignIds();
        when(users.findById(USER)).thenReturn(Optional.of(retailer()));
        Subscription dead = Subscription.builder()
                .id("dead").user(retailer()).plan(Plan.STARTER)
                .status(SubscriptionStatus.EXPIRED)
                .purchasedProjectCredits(3).reservedProjects(1).build();
        when(subs.findWithUnspentCredits(eq(USER), anyString())).thenReturn(List.of(dead));

        service.ensureCurrentCycle(USER);

        verify(subs).addPurchasedProjectCredits(anyString(), eq(3));
        verify(subs).addReservedProjects(anyString(), eq(1));
        assertThat(dead.getPurchasedProjectCredits()).isZero();
        assertThat(dead.getReservedProjects()).isZero();
    }

    /** A due cycle is renewed rather than duplicated — the shop keeps one free row, not
     *  a new one each month. */
    @Test
    void a_due_cycle_is_renewed_not_replaced() {
        Subscription sub = lapsedFreeCycle();
        dueFreeCycles(List.of(sub));

        service.ensureCurrentCycle(USER);

        assertThat(sub.getProjectsUsed()).isZero();
        verify(subs).save(sub);
        verify(users, never()).findById(anyString());
    }

    /** Colour matching is the line between the free tier and the paid ones. */
    @Test
    void the_free_tier_is_the_only_one_without_colour_matching() {
        assertThat(Plan.FREE.isColorMatching()).isFalse();
        assertThat(Plan.STARTER.isColorMatching()).isTrue();
        assertThat(Plan.PROFESSIONAL.isColorMatching()).isTrue();
        assertThat(Plan.BUSINESS.isColorMatching()).isTrue();
    }

    /** ₹199, or 80 points — the dearest extra-project rate on the ladder, which is what
     *  makes subscribing the cheaper way to buy volume. This is also the price a customer
     *  buying a project of their own pays, since a customer account never holds a plan. */
    @Test
    void an_extra_project_on_the_free_tier_costs_199_rupees_or_80_points() {
        assertThat(Plan.FREE.getExtraProjectPoints()).isEqualTo(80);
        assertThat(Plan.FREE.extraProjectPriceWithTaxInPaise()).isEqualTo(19900);
        assertThat(Plan.FREE.getExtraProjectPoints())
                .isGreaterThan(Plan.STARTER.getExtraProjectPoints());
    }

    /** Two projects, every month — the whole point of the tier. */
    @Test
    void the_free_tier_includes_two_projects_a_month() {
        assertThat(Plan.FREE.getMonthlyProjectLimit()).isEqualTo(2);
        assertThat(Plan.FREE.getPriceInPaise()).isZero();
    }
}
