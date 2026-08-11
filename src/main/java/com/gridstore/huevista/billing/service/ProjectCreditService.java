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
    private final com.gridstore.huevista.billing.repository.SubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final PricingService pricingService;
    private final BillingEmailService billingEmailService;
    private final RewardPointsService rewardPointsService;
    private final BillingService billingService;
    private final com.gridstore.huevista.auth.repository.UserRepository userRepository;

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
                .bundleCredits(PricingService.BUNDLE_CREDITS)
                .bundlePricePaise(pricingService.bundlePricePaise(userId))
                .reopenPricePoints(pricingService.pointsPriceReopen())
                .reopenPricePaise(pricingService.reopenPricePaise())
                .pointsBalance(rewardPointsService.balance(userId))
                .pointsEligible(rewardPointsService.canSpendPoints(userId))
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
     * Credit a three-project bundle: two projects the buyer paid for, and one they did not.
     *
     * The free one is issued as a {@link ProjectCredit.Source#GRANT} rather than a third
     * PURCHASE so the ledger keeps telling the truth about what money bought. GRANT already
     * means "issued without a payment behind it", which is exactly what this is, and it
     * costs no new enum value — and so no new value in a column a CHECK constraint might
     * be watching.
     *
     * A subscribed shop's credits land on the plan instead of in the ledger, where the
     * distinction has nowhere to live; there the bundle is simply three added to the
     * allowance. That is the same trade {@link #grantOneProject} already makes.
     */
    @Transactional
    public void creditPurchasedBundle(String userId) {
        for (int i = 0; i < PricingService.BUNDLE_PAID_FOR; i++) {
            grantOneProject(userId, 0, ProjectCredit.Source.PURCHASE);
        }
        for (int i = PricingService.BUNDLE_PAID_FOR; i < PricingService.BUNDLE_CREDITS; i++) {
            grantOneProject(userId, 0, ProjectCredit.Source.GRANT);
        }
    }

    /**
     * Hold {@code projects} against this shop's plan for a customer assignment, drawing on
     * projects it bought outright when the monthly allowance alone will not cover them.
     * All-or-nothing, like the plain reservation it wraps.
     *
     * Extras bought while a plan was live are already part of what the reservation counts
     * ({@code purchasedProjectCredits}); ones bought between plans are not, and are pulled
     * across here. Only the SHORTFALL moves, and only after the plan has been asked once: a
     * credit sitting in the ledger still works when the plan lapses and one moved onto the
     * plan does not, so nothing is relocated that this assignment does not actually need.
     */
    @Transactional
    public boolean reserveIncludingBoughtExtras(String ownerId, String subscriptionId, int projects) {
        if (subscriptionRepository.reserveProjectsIfWithinLimit(subscriptionId, projects) == 1) {
            return true;
        }
        int shortfall = subscriptionRepository.findById(subscriptionId)
                .map(sub -> projects - assignableHeadroom(sub))
                .orElse(projects);
        if (shortfall > 0 && transferLedgerCreditsToPlan(ownerId, shortfall) == 0) {
            return false;
        }
        return subscriptionRepository.reserveProjectsIfWithinLimit(subscriptionId, projects) == 1;
    }

    /**
     * What a plan can still hold: the monthly allowance plus everything bought or carried
     * over, less what is already spent or held. Widened to a long on the way through because
     * an unlimited tier carries {@code Integer.MAX_VALUE} as its limit, and adding bought
     * extras to that wraps negative.
     */
    private static int assignableHeadroom(com.gridstore.huevista.billing.model.Subscription sub) {
        long capacity = (long) sub.getProjectsLimit()
                + sub.getPurchasedProjectCredits() + sub.getCarriedProjectCredits();
        long headroom = capacity - sub.getProjectsUsed() - sub.getReservedProjects();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, headroom));
    }

    /**
     * Move up to {@code wanted} standalone credits onto the account's live plan, so extras
     * bought outright can be spent wherever the plan's own allowance is spent. Returns how
     * many actually moved.
     *
     * A project bought while the shop was between plans lands in the ledger, and the ledger
     * is only ever read by project CREATION — which meant a shop that bought three extras,
     * then subscribed, could paint three rooms itself but could not give one of them to a
     * customer: assigning draws on the plan's allowance, and the plan had never heard of
     * those three. They were bought and paid for either way, so the honest fix is to let
     * them follow the shop rather than making it guess which pool a purchase went into.
     *
     * Claim-then-credit rather than the reverse: the claim is the compare-and-set that
     * decides who gets the credit, so doing it first means a parallel project creation can
     * never spend a credit this call has already promised to the plan. If there turns out
     * to be no live plan to move them onto, every claim is handed straight back.
     */
    @Transactional
    public int transferLedgerCreditsToPlan(String userId, int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        var claimed = new java.util.ArrayList<String>(wanted);
        while (claimed.size() < wanted) {
            var credit = creditLedger.claim(userId);
            if (credit.isEmpty()) break;
            claimed.add(credit.get().getId());
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        if (billingService.creditPurchasedProjects(userId, claimed.size()).isEmpty()) {
            claimed.forEach(creditLedger::release);
            return 0;
        }
        log.info("Moved {} bought project(s) from the credit ledger onto the live plan: user={}",
                claimed.size(), userId);
        return claimed.size();
    }

    /**
     * Give a project another validity window after a verified CASH payment.
     *
     * The access check has already happened at order time; re-running it here would let a
     * shop that subscribed between paying and returning lose the window it just bought.
     */
    @Transactional
    public ProjectReopenResponse creditReopen(String userId, String projectId, int amountPaise) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        projectAccessService.reopenClosed(project);
        projectAccessService.extendWindow(project, pricingService.projectValidDays());
        projectRepository.save(project);
        log.info("Project reopened with money: user={} project={} until={} paise={}",
                userId, project.getId(), project.getAccessExpiresAt(), amountPaise);
        return reopenResult(project, 0, amountPaise);
    }

    /**
     * Refuse a reopen this account does not need, BEFORE any money moves.
     *
     * Shared by both rails so the points path and the cash path can never disagree about
     * whether a project is locked.
     */
    @Transactional(readOnly = true)
    public Project requireReopenable(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        com.gridstore.huevista.auth.model.User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        projectAccessService.assertNeedsReopen(userId, user.getRole(), project);
        return project;
    }

    // ── Extra AI renders ────────────────────────────────────────────────────

    /**
     * Refuse a render top-up this project does not need, BEFORE any money moves.
     *
     * Mirrors {@link #requireReopenable}: a project with a render still unspent is not
     * something to sell another one for, and finding that out after the payment sheet has
     * closed is the failure this exists to prevent.
     */
    @Transactional(readOnly = true)
    public Project requireRenderTopUp(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.hasRenderLeft()) {
            throw new IllegalStateException(
                    "This project still has an AI image to make — there's nothing to buy yet.");
        }
        return project;
    }

    /** Add one render to a project after a verified payment. */
    @Transactional
    public void creditExtraRender(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        project.setRendersAllowed(project.getRendersAllowed() + 1);
        projectRepository.save(project);
        log.info("Extra AI render credited: user={} project={} allowed={}",
                userId, projectId, project.getRendersAllowed());
    }

    /**
     * Give a project another validity window, paid in points.
     *
     * The project id comes from the request rather than from a verified order, which is
     * safe because the lookup is scoped to the caller ({@code findByIdAndUserId}) and the
     * points are their own either way.
     *
     * Refused when the caller can already work on the project — see
     * {@link ProjectAccessService#assertNeedsReopen}.
     */
    @Transactional
    public ProjectReopenResponse reopenWithPoints(String userId, String projectId) {
        Project project = requireReopenable(userId, projectId);

        // Priced off the project, not off a flat number: reopening a CLOSED project is a
        // different purchase from reopening a lapsed one, and the two rails have to agree
        // about which is which or the points path undercuts the cash path.
        int points = pricingService.pointsPriceReopen(project.isClosed());
        rewardPointsService.spend(userId, points,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT_REOPEN, project.getId());

        projectAccessService.reopenClosed(project);
        projectAccessService.extendWindow(project, pricingService.projectValidDays());
        projectRepository.save(project);
        log.info("Project reopened with points: user={} project={} until={} points={}",
                userId, project.getId(), project.getAccessExpiresAt(), points);

        billingEmailService.sendProjectReopened(userId, points, 0, pricingService.projectValidDays());
        return reopenResult(project, points, 0);
    }

    private ProjectReopenResponse reopenResult(Project project, int pointsSpent, int amountPaise) {
        return ProjectReopenResponse.builder()
                .projectId(project.getId())
                .accessExpiresAt(project.getAccessExpiresAt())
                .paused(project.getAccessPausedAt() != null)
                .pointsSpent(pointsSpent)
                .amountPaise(amountPaise)
                .daysAdded(pricingService.projectValidDays())
                .build();
    }
}
