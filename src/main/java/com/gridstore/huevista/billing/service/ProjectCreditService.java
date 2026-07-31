package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying one extra project, and buying another window on one whose validity has run out.
 *
 * There is a single "buy a project" operation, and where the credit LANDS depends on
 * whether a plan is covering the account:
 *
 * <ul>
 *   <li><b>On a plan</b> — it extends that plan's monthly allowance
 *       ({@code purchasedProjectCredits}). That is the shop that has run out mid-month
 *       and wants to keep working.</li>
 *   <li><b>Between plans</b> — it becomes a standalone {@link ProjectCredit} ledger row,
 *       which is what lets a shop with no subscription create a project at all.</li>
 * </ul>
 *
 * Both used to be separate purchases with separate prices ("an extra image", "an extra
 * auto-mask", "a project"), which meant a shop could buy the wrong one and still be
 * unable to finish a run. One unit, one purchase, one price — the plan's own rate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCreditService {

    private final ProjectCreditLedger creditLedger;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final PricingService pricingService;
    private final BillingEmailService billingEmailService;
    private final RewardPointsService rewardPointsService;
    private final BillingService billingService;

    /**
     * What a project costs this account on both rails and whether they can afford one —
     * enough for the UI to show the price and disable the button without a second round
     * trip.
     */
    @Transactional(readOnly = true)
    public ProjectPurchaseOptionsResponse getOptions(String userId) {
        Plan pricedAs = pricingService.pricingPlanFor(userId);
        return ProjectPurchaseOptionsResponse.builder()
                .subscribed(pricingService.isSubscribed(userId))
                .pricingPlan(pricedAs.name())
                .projectPricePoints(pricedAs.getExtraProjectPoints())
                .projectPricePaise(pricedAs.extraProjectPriceWithTaxInPaise())
                .reopenPricePoints(pricingService.pointsPriceReopen())
                .pointsBalance(rewardPointsService.balance(userId))
                .validDays(pricingService.projectValidDays())
                .availableCredits(creditLedger.available(userId))
                .build();
    }

    /**
     * Buy one project with points, at the account's own plan rate.
     *
     * The price is resolved server-side from the buyer's plan; a client never names it.
     * Quoting it back to the client and trusting the reply is how a Business rate gets
     * claimed by an account with no plan at all.
     */
    @Transactional
    public ProjectPurchaseOptionsResponse payWithPoints(String userId) {
        int points = pricingService.pointsPriceProject(userId);
        rewardPointsService.spend(userId, points,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
        grantOneProject(userId, points, ProjectCredit.Source.POINTS);
        log.info("Project bought with points: user={} points={}", userId, points);
        billingEmailService.sendProjectCreditPurchased(userId, points);
        return getOptions(userId);
    }

    /**
     * Land one bought project wherever it is worth something to this account: on the live
     * plan's allowance if there is one, otherwise in the standalone credit ledger.
     *
     * Issuing a ledger row to a subscribed shop would work but read as a second, parallel
     * pool it has to remember to spend; adding it to the plan puts it in the one number
     * the dashboard already shows.
     */
    private void grantOneProject(String userId, int pointsSpent, ProjectCredit.Source source) {
        if (billingService.creditPurchasedProjects(userId, 1).isPresent()) {
            return;
        }
        creditLedger.issue(userId, pointsSpent, pricingService.projectValidDays(), source);
    }

    /** Credit one project bought with money — the cash rail's landing point. */
    @Transactional
    public void creditPurchasedProject(String userId, ProjectCredit.Source source) {
        grantOneProject(userId, 0, source);
    }

    /**
     * Give a project another validity window, paid in points.
     *
     * The project id comes from the request rather than from a verified order, which is
     * safe because the lookup is scoped to the caller ({@code findByIdAndUserId}) and the
     * points are their own either way.
     */
    @Transactional
    public ProjectReopenResponse reopenWithPoints(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.isAccessWindowOpen()) {
            throw new IllegalStateException("This project is still open — there's nothing to reopen yet.");
        }

        int points = pricingService.pointsPriceReopen();
        rewardPointsService.spend(userId, points,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT_REOPEN, project.getId());

        projectAccessService.extendWindow(project, pricingService.projectValidDays());
        projectRepository.save(project);
        log.info("Project reopened with points: user={} project={} until={} points={}",
                userId, project.getId(), project.getAccessExpiresAt(), points);

        return ProjectReopenResponse.builder()
                .projectId(project.getId())
                .accessExpiresAt(project.getAccessExpiresAt())
                .paused(project.getAccessPausedAt() != null)
                .pointsSpent(points)
                .daysAdded(pricingService.projectValidDays())
                .build();
    }
}
