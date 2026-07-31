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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private ProjectCreditLedger ledger;
    private ProjectRepository projects;
    private BillingService billing;
    private PricingService pricing;
    private RewardPointsService points;
    private com.gridstore.huevista.auth.repository.UserRepository users;
    private ProjectCreditService svc;

    @BeforeEach
    void setUp() {
        ledger = mock(ProjectCreditLedger.class);
        projects = mock(ProjectRepository.class);
        billing = mock(BillingService.class);
        points = mock(RewardPointsService.class);

        pricing = new PricingService(billing, mock(OrgMembershipRepository.class));
        ReflectionTestUtils.setField(pricing, "projectValidDays", VALID_DAYS);
        ReflectionTestUtils.setField(pricing, "pointsPriceReopen", POINTS_REOPEN);
        ReflectionTestUtils.setField(pricing, "reopenPricePaise", REOPEN_PAISE);
        ReflectionTestUtils.setField(pricing, "currency", "INR");

        users = mock(com.gridstore.huevista.auth.repository.UserRepository.class);
        com.gridstore.huevista.auth.model.User retailer = new com.gridstore.huevista.auth.model.User();
        retailer.setId(USER);
        retailer.setRole(com.gridstore.huevista.auth.model.UserRole.RETAILER);
        when(users.findById(USER)).thenReturn(Optional.of(retailer));

        svc = new ProjectCreditService(ledger, projects,
                new ProjectAccessService(projects, billing, pricing), pricing,
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
                .accessExpiresAt(LocalDateTime.now().plusDays(5))
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
                .accessExpiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(projects.findByIdAndUserId("proj-2", USER)).thenReturn(Optional.of(lapsed));

        var res = svc.reopenWithPoints(USER, "proj-2");

        verify(points).spend(USER, POINTS_REOPEN,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT_REOPEN, "proj-2");
        assertThat(res.getPointsSpent()).isEqualTo(POINTS_REOPEN);
        assertThat(res.getDaysAdded()).isEqualTo(VALID_DAYS);
    }
}
