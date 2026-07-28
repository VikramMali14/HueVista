package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
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
 * Buying project access with points: a NEW project, or another window on one whose
 * validity has run out.
 *
 * Both used to be Razorpay orders at prices that changed with subscription state, which
 * meant a signature check, an order fetch and a set of acceptable amounts to guard
 * against paying the cheap price and claiming the dear one. None of that exists now:
 * points are the only way to buy a project, the price is flat, and the money was already
 * verified when the points were bought or earned. What is left is a balance check and a
 * ledger row.
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

    /**
     * What a project costs and whether this account can afford one — enough for the UI to
     * show the price and disable the button without a second round trip.
     */
    @Transactional(readOnly = true)
    public ProjectPurchaseOptionsResponse getOptions(String userId) {
        return ProjectPurchaseOptionsResponse.builder()
                .subscribed(pricingService.isSubscribed(userId))
                .projectPricePoints(pricingService.pointsPriceProject())
                .reopenPricePoints(pricingService.pointsPriceReopen())
                .pointsBalance(rewardPointsService.balance(userId))
                .validDays(pricingService.projectValidDays())
                .availableCredits(creditLedger.available(userId))
                .build();
    }

    /**
     * Buy one project with points.
     *
     * The credit is a ledger row rather than a counter bump, so the points it cost and
     * the window it opens travel with it to whichever project it eventually becomes.
     */
    @Transactional
    public ProjectPurchaseOptionsResponse payWithPoints(String userId) {
        int points = pricingService.pointsPriceProject();
        rewardPointsService.spend(userId, points,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
        creditLedger.issue(userId, points, pricingService.projectValidDays(),
                ProjectCredit.Source.POINTS);
        log.info("Project credit bought with points: user={} points={}", userId, points);
        billingEmailService.sendProjectCreditPurchased(userId, points);
        return getOptions(userId);
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
