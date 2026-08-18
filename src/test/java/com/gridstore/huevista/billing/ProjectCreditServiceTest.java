package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.service.BillingEmailService;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.ProjectCreditLedger;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.billing.service.RewardPointsService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Buying one extra project with points, and reopening a lapsed one.
 *
 * What has to hold: the price comes off the buyer's own PLAN rather than a constant, the
 * balance is the gate, the credit lands on the subscription when there is one and in the
 * ledger when there isn't, and a caller can only ever reopen a project that is theirs and
 * actually lapsed.
 */
class ProjectCreditServiceTest {

    private static final String USER = "user-1";
    private static final int VALID_DAYS = 30;
    /** The no-plan rate — what an unsubscribed shop pays. */
    private static final int POINTS_PROJECT = Plan.FREE.getExtraProjectPoints();
    private static final int POINTS_REOPEN = 9;
    private static final int REOPEN_PAISE = 1000;
    private static final int REOPEN_CLOSED_PAISE = 9900;
    private static final int POINTS_REOPEN_CLOSED = 99;

    private ProjectCreditLedger ledger;
    private com.gridstore.huevista.billing.repository.SubscriptionRepository subscriptions;
    private ProjectRepository projects;
    private BillingService billing;
    private PricingService pricing;
    private RewardPointsService points;
    private com.gridstore.huevista.auth.repository.UserRepository users;
    private ProjectCreditService svc;

    @BeforeEach
    void setUp() {
        ledger = mock(ProjectCreditLedger.class);
        subscriptions = mock(com.gridstore.huevista.billing.repository.SubscriptionRepository.class);
        projects = mock(ProjectRepository.class);
        billing = mock(BillingService.class);
        points = mock(RewardPointsService.class);

        pricing = new PricingService(billing, mock(com.gridstore.huevista.billing.service.UnbilledAccounts.class),
                mock(OrgMembershipRepository.class),
                mock(com.gridstore.huevista.auth.repository.UserRepository.class));
        ReflectionTestUtils.setField(pricing, "projectValidDays", VALID_DAYS);
        ReflectionTestUtils.setField(pricing, "pointsPriceReopen", POINTS_REOPEN);
        ReflectionTestUtils.setField(pricing, "reopenPricePaise", REOPEN_PAISE);
        ReflectionTestUtils.setField(pricing, "reopenClosedPricePaise", REOPEN_CLOSED_PAISE);
        ReflectionTestUtils.setField(pricing, "pointsPriceReopenClosed", POINTS_REOPEN_CLOSED);
        ReflectionTestUtils.setField(pricing, "currency", "INR");

        users = mock(com.gridstore.huevista.auth.repository.UserRepository.class);
        com.gridstore.huevista.auth.model.User retailer = new com.gridstore.huevista.auth.model.User();
        retailer.setId(USER);
        retailer.setRole(com.gridstore.huevista.auth.model.UserRole.RETAILER);
        when(users.findById(USER)).thenReturn(Optional.of(retailer));

        svc = new ProjectCreditService(ledger, subscriptions, projects,
                new ProjectAccessService(projects, billing, pricing,
                        mock(com.gridstore.huevista.account.repository.CustomerEntitlementRepository.class)), pricing,
                mock(BillingEmailService.class), points, billing, users);

        // Default: nobody is subscribed. Individual tests opt in.
        when(billing.findEntitlingSubscription(any())).thenReturn(Optional.empty());
        when(billing.creditPurchasedProjects(any(), anyInt())).thenReturn(Optional.empty());
    }

    @Test
    void withNoPlanAProjectCostsTheDearestRateAndLandsInTheLedger() {
        svc.payWithPoints(USER);

        verify(points).spend(USER, POINTS_PROJECT,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
        verify(ledger).issue(USER, POINTS_PROJECT, VALID_DAYS, ProjectCredit.Source.POINTS);
    }

    /**
     * The price falls with the plan — that discount is the reason a shop that keeps buying
     * extras is better off moving up a tier than paying the no-plan rate forever. It also
     * has to be read server-side from the live subscription: a client that could name its
     * tier could name Business.
     */
    @Test
    void aSubscribedShopPaysItsOwnTiersRate() {
        subscribedOn(Plan.BUSINESS);

        svc.payWithPoints(USER);

        verify(points).spend(USER, Plan.BUSINESS.getExtraProjectPoints(),
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
    }

    /**
     * A trial is granted, not bought, so it does not carry a paid tier's discount — it
     * pays the no-plan rate like any other account without a subscription behind it.
     */
    @Test
    void aFreeTrialPaysTheNoPlanRate() {
        Subscription trial = subscribedOn(Plan.STARTER);
        trial.setTrial(true);

        svc.payWithPoints(USER);

        verify(points).spend(USER, POINTS_PROJECT,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
    }

    /**
     * A shop WITH a plan gets the extra added to that plan's allowance rather than issued
     * as a standalone credit. Two parallel pools of projects is one more thing than the
     * dashboard should have to explain.
     */
    @Test
    void aSubscribedShopsExtraExtendsThePlanRatherThanTheLedger() {
        subscribedOn(Plan.STARTER);
        when(billing.creditPurchasedProjects(USER, 1))
                .thenReturn(Optional.of(mock(com.gridstore.huevista.billing.dto.SubscriptionResponse.class)));

        svc.payWithPoints(USER);

        verify(billing).creditPurchasedProjects(USER, 1);
        verify(ledger, never()).issue(any(), anyInt(), anyInt(), any());
    }

    private Subscription subscribedOn(Plan plan) {
        Subscription sub = new Subscription();
        sub.setPlan(plan);
        when(billing.findEntitlingSubscription(USER)).thenReturn(Optional.of(sub));
        return sub;
    }

    /** The debit is the gate — an insufficient balance must leave no credit behind. */
    @Test
    void tooFewPointsIssuesNoCredit() {
        doThrow(new QuotaExceededException("Not enough points"))
                .when(points).spend(any(), anyInt(), any(), any());

        assertThatThrownBy(() -> svc.payWithPoints(USER)).isInstanceOf(QuotaExceededException.class);
        verify(ledger, never()).issue(any(), anyInt(), anyInt(), any());
    }

    @Test
    void optionsQuoteThePointPricesAndTheBalance() {
        when(points.balance(USER)).thenReturn(200);
        when(points.canSpendPoints(USER)).thenReturn(true);
        when(ledger.available(USER)).thenReturn(2);

        var options = svc.getOptions(USER);

        assertThat(options.getProjectPricePoints()).isEqualTo(POINTS_PROJECT);
        assertThat(options.getProjectPricePaise())
                .isEqualTo(Plan.FREE.extraProjectPriceWithTaxInPaise());
        assertThat(options.getPricingPlan()).isEqualTo("FREE");
        assertThat(options.getReopenPricePoints()).isEqualTo(POINTS_REOPEN);
        assertThat(options.getReopenPricePaise()).isEqualTo(REOPEN_PAISE);
        assertThat(options.getPointsBalance()).isEqualTo(200);
        assertThat(options.getAvailableCredits()).isEqualTo(2);
        assertThat(options.getValidDays()).isEqualTo(VALID_DAYS);
        assertThat(options.isSubscribed()).isFalse();
        assertThat(options.isPointsEligible()).isTrue();
    }

    /**
     * An account that cannot hold points is told so, separately from its balance.
     *
     * A CUSTOMER reads a balance of zero either way, and "zero" alone reads as "top up" —
     * which is how a self-signed-up customer ended up being offered a points button that
     * could only ever come back 403. The cash price is still quoted, because money IS
     * their rail: it is the one that lands them a credit to create a project with.
     */
    @Test
    void optionsSayWhenThePointsRailIsClosedToThisAccount() {
        when(points.balance(USER)).thenReturn(0);
        when(points.canSpendPoints(USER)).thenReturn(false);

        var options = svc.getOptions(USER);

        assertThat(options.isPointsEligible()).isFalse();
        assertThat(options.getProjectPricePaise())
                .isEqualTo(Plan.FREE.extraProjectPriceWithTaxInPaise());
    }

    // ── Assigning a bought project to a customer ────────────────────────────

    private static final String SUB = "sub-1";

    /** Nothing is relocated while the plan can still cover the assignment on its own. */
    @Test
    void assigningWithinThePlansOwnAllowanceLeavesTheLedgerAlone() {
        when(subscriptions.reserveProjectsIfWithinLimit(SUB, 2)).thenReturn(1);

        assertThat(svc.reserveIncludingBoughtExtras(USER, SUB, 2)).isTrue();
        verify(ledger, never()).claim(any());
    }

    /**
     * The case this exists for: a shop bought extras while it had no plan, so they sat in
     * the ledger where only project CREATION could see them — it could paint those rooms
     * itself but was told it had nothing to give a customer. Only the shortfall moves.
     */
    @Test
    void anExtraBoughtBetweenPlansCanStillBeAssignedToACustomer() {
        Subscription spent = new Subscription();
        spent.setProjectsLimit(15);
        spent.setProjectsUsed(15);
        when(subscriptions.findById(SUB)).thenReturn(Optional.of(spent));
        when(subscriptions.reserveProjectsIfWithinLimit(SUB, 1)).thenReturn(0, 1);
        when(ledger.claim(USER)).thenReturn(Optional.of(new ProjectCredit()));
        when(billing.creditPurchasedProjects(USER, 1))
                .thenReturn(Optional.of(mock(com.gridstore.huevista.billing.dto.SubscriptionResponse.class)));

        assertThat(svc.reserveIncludingBoughtExtras(USER, SUB, 1)).isTrue();
        verify(billing).creditPurchasedProjects(USER, 1);
    }

    /** A spent plan with nothing bought behind it is a real refusal, not a silent free one. */
    @Test
    void assigningIsRefusedWhenNoBoughtProjectIsAvailableToCoverIt() {
        Subscription spent = new Subscription();
        spent.setProjectsLimit(15);
        spent.setProjectsUsed(15);
        when(subscriptions.findById(SUB)).thenReturn(Optional.of(spent));
        when(subscriptions.reserveProjectsIfWithinLimit(SUB, 1)).thenReturn(0);
        when(ledger.claim(USER)).thenReturn(Optional.empty());

        assertThat(svc.reserveIncludingBoughtExtras(USER, SUB, 1)).isFalse();
        verify(billing, never()).creditPurchasedProjects(any(), anyInt());
    }

    /**
     * The claim happens before the credit lands, so that a parallel project creation can
     * never spend one this call has promised. If there turns out to be no plan to land it
     * on, it has to go straight back — a claim held onto is a project the shop paid for
     * and can no longer use.
     */
    @Test
    void aClaimedCreditGoesBackWhenThereIsNoPlanToMoveItOnto() {
        ProjectCredit credit = new ProjectCredit();
        credit.setId("credit-1");
        when(ledger.claim(USER)).thenReturn(Optional.of(credit));
        when(billing.creditPurchasedProjects(USER, 1)).thenReturn(Optional.empty());

        assertThat(svc.transferLedgerCreditsToPlan(USER, 1)).isZero();
        verify(ledger).release("credit-1");
    }

    // ── Reopen ──────────────────────────────────────────────────────────────

    @Test
    void reopeningOnlyEverTouchesTheCallersOwnProject() {
        when(projects.findByIdAndUserId("proj-not-theirs", USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.reopenWithPoints(USER, "proj-not-theirs"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(points, never()).spend(any(), anyInt(), any(), any());
    }

    /** Paying to extend something that has not run out is money for nothing. */
    @Test
    void aProjectThatIsStillOpenCannotBeReopened() {
        Project open = Project.builder()
                .id("proj-1")
                .build();
        when(projects.findByIdAndUserId("proj-1", USER)).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> svc.reopenWithPoints(USER, "proj-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
        verify(points, never()).spend(any(), anyInt(), any(), any());
    }

    /**
     * A plan-covered project carries NO window at all — null on every field — so the
     * obvious "is the window open?" guard read it as closed and happily charged a
     * subscriber to unlock something their plan was already unlocking. The guard has to
     * ask about ACCESS, not about the window.
     */
    @Test
    void aSubscribedShopIsNotChargedToReopenAProjectItsPlanAlreadyCovers() {
        subscribedOn(Plan.STARTER);
        Project planCovered = Project.builder().id("proj-3").build();
        when(projects.findByIdAndUserId("proj-3", USER)).thenReturn(Optional.of(planCovered));

        assertThatThrownBy(() -> svc.reopenWithPoints(USER, "proj-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
        verify(points, never()).spend(any(), anyInt(), any(), any());
    }

    /**
     * Same trap on the other route in: work created under a shop's access code is paid for
     * by that shop and stays fully editable for as long as the code lives, window or no
     * window.
     */
    @Test
    void anAccessCodeProjectIsNotChargedAReopenEither() {
        Project codeCovered = Project.builder()
                .id("proj-4")
                .accessCode(new com.gridstore.huevista.account.model.CustomerAccessCode())
                .build();
        when(projects.findByIdAndUserId("proj-4", USER)).thenReturn(Optional.of(codeCovered));

        assertThatThrownBy(() -> svc.reopenWithPoints(USER, "proj-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
        verify(points, never()).spend(any(), anyInt(), any(), any());
    }

    @Test
    void reopeningALapsedProjectSpendsTheReopenPriceAndExtendsIt() {
        Project lapsed = Project.builder()
                .id("proj-2")
                .build();
        when(projects.findByIdAndUserId("proj-2", USER)).thenReturn(Optional.of(lapsed));

        var res = svc.reopenWithPoints(USER, "proj-2");

        verify(points).spend(USER, POINTS_REOPEN,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT_REOPEN, "proj-2");
        assertThat(res.getPointsSpent()).isEqualTo(POINTS_REOPEN);
        assertThat(res.getDaysAdded()).isEqualTo(VALID_DAYS);
    }

    // ─── The three-project bundle ────────────────────────────────────────────

    @Test
    void aBundleCostsTwoProjectsAndGrantsThree() {
        assertThat(pricing.bundlePricePaise(USER))
                .isEqualTo(Plan.FREE.extraProjectPriceWithTaxInPaise() * 2);
        assertThat(PricingService.BUNDLE_CREDITS).isEqualTo(3);
    }

    @Test
    void aBundleIssuesTwoPurchasedCreditsAndOneGranted() {
        // The free one is not a PURCHASE: the ledger has to keep telling the truth about
        // what money actually bought, and GRANT already means "issued without a payment".
        svc.creditPurchasedBundle(USER);

        verify(ledger, times(2))
                .issue(eq(USER), eq(0), eq(VALID_DAYS), eq(ProjectCredit.Source.PURCHASE));
        verify(ledger, times(1))
                .issue(eq(USER), eq(0), eq(VALID_DAYS), eq(ProjectCredit.Source.GRANT));
    }

    @Test
    void aSubscribedShopsBundleLandsOnItsPlanInstead() {
        // On a plan there is nowhere for the purchased/granted distinction to live, so all
        // three simply become allowance — the same trade a single purchase already makes.
        when(billing.creditPurchasedProjects(any(), anyInt()))
                .thenReturn(Optional.of(mock(com.gridstore.huevista.billing.dto.SubscriptionResponse.class)));

        svc.creditPurchasedBundle(USER);

        verify(billing, times(3)).creditPurchasedProjects(USER, 1);
        verify(ledger, never()).issue(any(), anyInt(), anyInt(), any());
    }

    // ─── Reopening: two prices wearing one name ──────────────────────────────

    @Test
    void reopeningAClosedProjectCostsMoreThanReopeningALapsedOne() {
        assertThat(pricing.reopenPricePaise(false)).isEqualTo(REOPEN_PAISE);
        assertThat(pricing.reopenPricePaise(true)).isEqualTo(REOPEN_CLOSED_PAISE);
        assertThat(pricing.pointsPriceReopen(false)).isEqualTo(POINTS_REOPEN);
        assertThat(pricing.pointsPriceReopen(true)).isEqualTo(POINTS_REOPEN_CLOSED);
    }

    @Test
    void theNoArgReopenPriceStillMeansALapsedWindow() {
        // Existing callers must keep quoting the cheap reopen, not silently start
        // charging the closed rate.
        assertThat(pricing.reopenPricePaise()).isEqualTo(REOPEN_PAISE);
        assertThat(pricing.pointsPriceReopen()).isEqualTo(POINTS_REOPEN);
    }

}
