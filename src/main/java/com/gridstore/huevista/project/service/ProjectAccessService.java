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
 * Access comes from any ONE of four places, checked in this order:
 *
 * <ol>
 *   <li>The role. Administrators and distributors are never gated.</li>
 *   <li>A live subscription — the ordinary case, and the only one that covers every
 *       project the account owns at once.</li>
 *   <li>The shop access code a project was created under. The issuing shop already paid
 *       for that work at code-generation time, so the customer keeps working on it for as
 *       long as the code lives regardless of anyone's subscription.</li>
 *   <li>The project's own paid window — the project was bought outright.</li>
 * </ol>
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

    /** Why a project is view-only, and what it would take to unlock it. */
    public record Access(Mode mode, String reason, LocalDateTime expiresAt, int reopenPricePoints) {
        public boolean editable() {
            return mode == Mode.FULL;
        }
    }

    private static final String NO_SUBSCRIPTION =
            "Your subscription has ended, so this project is view-only — you can still see the "
            + "colours that were last applied. Subscribe to keep working on it.";

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
                    pricingService.pointsPriceReopen());
        }
        // A project that never carried a window at all belongs to an account that used to
        // be covered by a plan. It reads as subscription-lapsed rather than expired,
        // because buying a reopen would not be the right advice — resubscribing is.
        String reason = project.hasAccessWindow() ? windowLapsedMessage() : NO_SUBSCRIPTION;
        return new Access(Mode.VIEW_ONLY, reason, project.getAccessExpiresAt(),
                pricingService.pointsPriceReopen());
    }

    private Access full() {
        return new Access(Mode.FULL, null, null, pricingService.pointsPriceReopen());
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
