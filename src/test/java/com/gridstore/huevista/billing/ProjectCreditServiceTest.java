package com.gridstore.huevista.billing;

import com.gridstore.huevista.account.repository.OrgMembershipRepository;
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
 * Buying project access with points.
 *
 * The Razorpay order/verify machinery this used to guard is gone: projects and reopens
 * are bought with points, and the money was verified when the points were. What is left
 * to prove is that the price is flat, the balance is the gate, and a caller can only ever
 * reopen a project that is theirs and actually lapsed.
 */
class ProjectCreditServiceTest {

    private static final String USER = "user-1";
    private static final int VALID_DAYS = 30;
    private static final int POINTS_PROJECT = 80;
    private static final int POINTS_REOPEN = 9;

    private ProjectCreditLedger ledger;
    private ProjectRepository projects;
    private BillingService billing;
    private PricingService pricing;
    private RewardPointsService points;
    private ProjectCreditService svc;

    @BeforeEach
    void setUp() {
        ledger = mock(ProjectCreditLedger.class);
        projects = mock(ProjectRepository.class);
        billing = mock(BillingService.class);
        points = mock(RewardPointsService.class);

        pricing = new PricingService(billing, mock(OrgMembershipRepository.class));
        ReflectionTestUtils.setField(pricing, "projectValidDays", VALID_DAYS);
        ReflectionTestUtils.setField(pricing, "pointsPriceProject", POINTS_PROJECT);
        ReflectionTestUtils.setField(pricing, "pointsPriceReopen", POINTS_REOPEN);
        ReflectionTestUtils.setField(pricing, "currency", "INR");

        svc = new ProjectCreditService(ledger, projects,
                new ProjectAccessService(projects, billing, pricing), pricing,
                mock(BillingEmailService.class), points);

        // Default: nobody is subscribed. Individual tests opt in.
        when(billing.findEntitlingSubscription(any())).thenReturn(Optional.empty());
    }

    @Test
    void aProjectCostsTheFlatPointPrice() {
        svc.payWithPoints(USER);

        verify(points).spend(USER, POINTS_PROJECT,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
        verify(ledger).issue(USER, POINTS_PROJECT, VALID_DAYS, ProjectCredit.Source.POINTS);
    }

    /**
     * The cash price of a project used to halve with a live plan. The point price does
     * not move: points are a shop's own currency, and making them worth less to a lapsed
     * shop would blunt them exactly where they are supposed to help.
     */
    @Test
    void thePointPriceIsFlatWhateverThePlanIsDoing() {
        when(billing.findEntitlingSubscription(USER)).thenReturn(Optional.of(new Subscription()));

        svc.payWithPoints(USER);

        verify(points).spend(USER, POINTS_PROJECT,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
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
        assertThat(options.getReopenPricePoints()).isEqualTo(POINTS_REOPEN);
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
                .hasMessageContaining("still open");
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
