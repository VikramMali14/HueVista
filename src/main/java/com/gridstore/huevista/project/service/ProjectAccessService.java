package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.common.exception.SubscriptionRequiredException;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides, per project, whether the signed-in account may WORK on it or only LOOK at it.
 *
 * A lapsed subscription no longer hides a shop's work — the dashboard still shows every
 * room and every project still opens, showing the colours that were last applied. What
 * it withholds is change: recolouring, re-running AI, sharing. That is the difference
 * between {@link Mode#FULL} and {@link Mode#VIEW_ONLY}, and it is the only thing this
 * class is for.
 *
 * Access comes from any ONE of five places, checked in this order:
 *
 * <ol>
 *   <li>The role. Administrators and distributors are never gated.</li>
 *   <li>The free library. A room copied off the shelf is never gated by anything — see
 *       below.</li>
 *   <li>A live subscription — the ordinary case, and the only one that covers every
 *       project the account owns at once.</li>
 *   <li>The shop access code a project was created under. The issuing shop already paid
 *       for that work at code-generation time, so the customer keeps working on it for as
 *       long as the code lives regardless of anyone's subscription.</li>
 *   <li>The project's own paid window — the project was bought outright.</li>
 * </ol>
 *
 * <h2>Why a library room is unconditional</h2>
 * Every other rail in here is answering "who paid for this work?". For a room off the
 * free shelf the answer is nobody, and that is the product: the pixels were already
 * stored, no AI ran, no credit and no quota moved. So there is no clock to run out, and
 * nothing a reopen could be selling back. Left to the ordinary rules the opposite
 * happened — a copy carries no window at all, which reads as subscription-lapsed, so an
 * account without a plan got a room that opened and a palette that did not; and its one
 * colour board CLOSED it, which is a ₹99 reopen on a room we gave away. The check
 * therefore sits above closure and above every payment rail, because it has to outrank
 * all of them rather than merely one.
 *
 * <h2>Why the window pauses</h2>
 * A paid window is a number of DAYS OF USE, not a calendar deadline. Someone who buys a
 * 30-day project and then subscribes is not using those days — the subscription is
 * covering them — so the window stops and the remainder is parked. When the subscription
 * ends the remainder resumes. Parking the leftover rather than freezing an end date is
 * what makes {@link #reconcile} idempotent: running it twice in the same subscription
 * state is a no-op, which matters because it runs on every read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final BillingService billingService;
    private final PricingService pricingService;

    public enum Mode {
        /** Everything is open: recolour, segment, share, edit. */
        FULL,
        /** Readable only — the last applied colours show, but nothing can change. */
        VIEW_ONLY
    }

    /**
     * Why a project is view-only, and what it would take to unlock it.
     *
     * BOTH rails are quoted, and both are read from the project rather than from the
     * account, because reopening no longer has one price. A lapsed window costs ₹9 and a
     * closed project ₹99, and the account-level quote cannot tell which this is — it
     * knows the buyer, not the room. Quoting the wrong one puts a price on the banner
     * that the payment then refuses to match.
     */
    public record Access(Mode mode, String reason, LocalDateTime expiresAt,
                         int reopenPricePoints, int reopenPricePaise) {
        public boolean editable() {
            return mode == Mode.FULL;
        }
    }

    private static final String NO_SUBSCRIPTION =
            "Your subscription has ended, so this project is view-only — you can still see the "
            + "colours that were last applied. Subscribe to keep working on it.";

    private static final String CLOSED =
            "This project is closed. The shades from your colour boards are still here, and so "
            + "is your render — but the rest of the catalogue is locked. Reopen it to start "
            + "choosing again.";

    /** The window length is configured, so the sentence quoting it has to be built. */
    private String windowLapsedMessage() {
        return "This project's validity has ended, so it is view-only — the colours you last "
               + "applied are still here. Reopen it for another " + pricingService.projectValidDays()
               + " days, or subscribe.";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Reading access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The access this owner has to this project right now. Reconciles the project's
     * window against the owner's subscription first, so a plan that lapsed since the last
     * read resumes the paid days immediately rather than at the next nightly sweep.
     */
    @Transactional
    public Access accessFor(String userId, UserRole role, Project project) {
        boolean subscribed = pricingService.isSubscribed(userId);
        reconcile(project, subscribed, LocalDateTime.now());
        return evaluate(role, project, subscribed);
    }

    /**
     * Non-mutating variant for list views, where the caller has already reconciled the
     * whole page in one pass (see {@link #reconcileAll}).
     */
    public Access evaluate(UserRole role, Project project, boolean subscribed) {
        // Administrators and distributors run the platform rather than buy from it.
        if (role == UserRole.ADMIN || role == UserRole.DISTRIBUTOR) {
            return full();
        }
        // A room off the free shelf, which nothing may ever gate — not a lapsed plan, not
        // a spent board, not closure. Deliberately ABOVE the closure branch below: rooms
        // copied before this rule existed may still carry a closedAt (the migration clears
        // the ones it can see, and a race with an in-flight board could write another),
        // and a library room that reads as closed is exactly the ₹99 charge this exists to
        // remove. Answering here means the stored timestamp cannot decide anything.
        if (project.isFromLibrary()) {
            return full();
        }
        // Closure outranks every way of paying, which is why it is asked FIRST and not
        // somewhere below. A closed project is finished: the customer took their colour
        // boards and their render off it and said so. Whether a plan or a shop's access
        // code happens to be covering the account is a question about who pays for work,
        // and there is no more work — putting this check under the subscription branch
        // would have left a subscribed shop, and every customer on a live code, able to
        // keep editing a job they had already closed and been rendered for.
        if (project.isClosed()) {
            return new Access(Mode.VIEW_ONLY, CLOSED, project.getAccessExpiresAt(),
                    pricingService.pointsPriceReopen(true), pricingService.reopenPricePaise(true));
        }
        if (subscribed) {
            return full();
        }
        // Work created under a shop's access code was paid for by that shop when the code
        // was issued. Whether the person holding it subscribes to anything is irrelevant;
        // the code's own validity governs, and expiry there is enforced separately.
        if (project.getAccessCode() != null) {
            return full();
        }
        if (project.isAccessWindowOpen()) {
            return new Access(Mode.FULL, null, project.getAccessExpiresAt(),
                    pricingService.pointsPriceReopen(), pricingService.reopenPricePaise());
        }
        // A project that never carried a window at all belongs to an account that used to
        // be covered by a plan. It reads as subscription-lapsed rather than expired,
        // because buying a reopen would not be the right advice — resubscribing is.
        String reason = project.hasAccessWindow() ? windowLapsedMessage() : NO_SUBSCRIPTION;
        return new Access(Mode.VIEW_ONLY, reason, project.getAccessExpiresAt(),
                pricingService.pointsPriceReopen(), pricingService.reopenPricePaise());
    }

    private Access full() {
        return new Access(Mode.FULL, null, null,
                pricingService.pointsPriceReopen(), pricingService.reopenPricePaise());
    }

    /**
     * Gate for anything that CHANGES a project — recolour, segment, mask edits, share.
     * Throws 402 with a message the studio turns into a "view-only" banner.
     */
    @Transactional
    public void assertEditable(String userId, UserRole role, Project project) {
        Access access = accessFor(userId, role, project);
        if (!access.editable()) {
            throw new SubscriptionRequiredException(access.reason());
        }
    }

    /**
     * Gate for BUYING a reopen: refuses when this account can already work on the project,
     * so nobody is sold a window they do not need.
     *
     * The obvious guard — "does the project have an open window?" — is the wrong question,
     * and asking it took money for nothing. A project covered by a live subscription or by
     * a shop's access code carries no window AT ALL (null on every field), so it read as
     * closed while being fully editable: a subscriber could POST a reopen and be charged
     * to unlock something already unlocked. What matters is the caller's ACCESS, which is
     * the one thing this class exists to answer.
     */
    @Transactional
    public void assertNeedsReopen(String userId, UserRole role, Project project) {
        if (accessFor(userId, role, project).editable()) {
            throw new IllegalStateException(
                    "This project is already open — there's nothing to reopen. "
                    + "A reopen is only needed once its validity has run out.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Opening and extending windows
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Open a paid window on a freshly-bought project.
     *
     * The window is created even when the buyer is currently subscribed — immediately
     * paused, holding its full length. That is the point of pausing: the days they paid
     * for are banked, and the moment the subscription lapses they get all of them rather
     * than discovering the project they bought last month has silently expired.
     */
    public void openWindow(Project project, int validDays, int pointsSpent, boolean subscribed) {
        LocalDateTime now = LocalDateTime.now();
        project.setPurchasedAt(now);
        project.setPurchasePoints(pointsSpent);
        if (subscribed) {
            project.setAccessExpiresAt(null);
            project.setAccessPausedAt(now);
            project.setAccessRemainingSeconds(Duration.ofDays(validDays).toSeconds());
        } else {
            project.setAccessExpiresAt(now.plusDays(validDays));
            project.setAccessPausedAt(null);
            project.setAccessRemainingSeconds(null);
        }
    }

    /**
     * Finish a project. Idempotent: the first close wins and keeps its timestamp, so a
     * customer who presses the button just as their second colour board lands does not
     * get two closures with two different times on them.
     *
     * Returns true when this call is the one that closed it — the caller uses that to
     * decide whether to send the customer on to the render step.
     *
     * <p>A library room can never be closed, and this is the one place that is enforced
     * rather than at each of the two callers (the last colour board, and the customer
     * pressing "close project"). Closing is a purchase in disguise — what it does is make
     * a project view-only until ₹99 reopens it — and there is nothing on a free room for
     * that to be charged against. Refusing here is silent by design: the return value
     * already means "nothing to do", which is what both callers do with an
     * already-closed project, so neither needs to learn about library rooms to behave
     * correctly. An AI image never depended on closure anyway (see
     * {@link ProjectRenderService}), so nothing is withheld by declining.
     */
    public boolean close(Project project) {
        if (project.isFromLibrary() || project.isClosed()) {
            return false;
        }
        project.setClosedAt(LocalDateTime.now());
        return true;
    }

    /**
     * Undo a closure, as part of a paid reopen.
     *
     * The colour-board count goes back to zero with it. A reopened project is being
     * bought a second run at the same job — the catalogue comes back, so the two boards
     * that produce the next set of combos have to come back too, or the customer pays ₹99
     * for a project that can no longer hand them anything. The pages already recorded are
     * kept: they are what the customer was given the first time round, and the next boards
     * are numbered after them.
     *
     * The render allowance is deliberately NOT reset. Renders are bought per image, and a
     * reopen buys catalogue access, not another ₹99 render on top.
     */
    public void reopenClosed(Project project) {
        project.setClosedAt(null);
        project.setColourBoardsUsed(0);
    }

    /**
     * Add another window to a project — the paid reopen.
     *
     * Extending a window that is still running ADDS to it rather than restarting it, so
     * reopening early never destroys days already paid for. A paused window grows its
     * parked remainder for the same reason.
     */
    public void extendWindow(Project project, int extraDays) {
        LocalDateTime now = LocalDateTime.now();
        long extraSeconds = Duration.ofDays(extraDays).toSeconds();

        if (project.getAccessPausedAt() != null) {
            long parked = project.getAccessRemainingSeconds() == null
                    ? 0 : project.getAccessRemainingSeconds();
            project.setAccessRemainingSeconds(parked + extraSeconds);
            return;
        }
        LocalDateTime from = project.getAccessExpiresAt() != null
                && project.getAccessExpiresAt().isAfter(now)
                ? project.getAccessExpiresAt() : now;
        project.setAccessExpiresAt(from.plusSeconds(extraSeconds));
        project.setAccessRemainingSeconds(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Pause / resume
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bring one project's window in line with its owner's current subscription state.
     * Returns true when something actually moved, so callers can skip a pointless save.
     */
    public boolean reconcile(Project project, boolean subscribed, LocalDateTime now) {
        if (!project.hasAccessWindow()) {
            return false;
        }
        if (subscribed && project.getAccessPausedAt() == null) {
            long remaining = project.getAccessExpiresAt() == null ? 0
                    : Math.max(0, Duration.between(now, project.getAccessExpiresAt()).getSeconds());
            project.setAccessRemainingSeconds(remaining);
            project.setAccessPausedAt(now);
            project.setAccessExpiresAt(null);
            return true;
        }
        if (!subscribed && project.getAccessPausedAt() != null) {
            long remaining = project.getAccessRemainingSeconds() == null
                    ? 0 : project.getAccessRemainingSeconds();
            project.setAccessExpiresAt(now.plusSeconds(remaining));
            project.setAccessPausedAt(null);
            project.setAccessRemainingSeconds(null);
            return true;
        }
        return false;
    }

    /** Reconcile a whole page of projects in one pass — the list-view entry point. */
    @Transactional
    public void reconcileAll(List<Project> projects, boolean subscribed) {
        LocalDateTime now = LocalDateTime.now();
        for (Project project : projects) {
            if (reconcile(project, subscribed, now)) {
                projectRepository.save(project);
            }
        }
    }

    /**
     * Nightly safety net, an hour after the subscription expiry job so the two never
     * interleave: resume the windows of every project whose owner is no longer covered.
     *
     * Reads reconcile lazily, which is enough for anyone who opens the app — but a
     * project whose owner never comes back would otherwise sit paused forever, and its
     * paid days would never be spent. Whether that matters is a judgement call; running
     * it on a schedule means the stored state matches what the account is actually
     * entitled to, rather than depending on someone logging in to find out.
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 2 * * *")
    @Transactional
    public void resumeLapsedWindows() {
        List<Project> paused = projectRepository.findPausedWindows();
        if (paused.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        // One subscription lookup per OWNER, not per project. An account with a dozen
        // banked projects is the ordinary case, and asking the same question twelve
        // times is how a nightly sweep quietly becomes the slowest job on the box.
        Map<String, Boolean> subscribedByOwner = new HashMap<>();
        int resumed = 0;
        for (Project project : paused) {
            String ownerId = project.getUser() != null ? project.getUser().getId() : null;
            if (ownerId == null) continue;
            boolean subscribed = subscribedByOwner.computeIfAbsent(ownerId,
                    id -> billingService.findEntitlingSubscription(id).isPresent());
            if (reconcile(project, subscribed, now)) {
                projectRepository.save(project);
                resumed++;
            }
        }
        if (resumed > 0) {
            log.info("Resumed {} paused project validity window(s) across {} account(s) "
                    + "after subscriptions lapsed", resumed, subscribedByOwner.size());
        }
    }
}
