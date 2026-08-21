package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.notification.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Payment / subscription lifecycle emails, sent from the billing address
 * ({@code app.mail.billing-from}, e.g. payments@huevista.org) rather than the
 * generic no-reply sender so receipts are recognisable and filterable.
 *
 * Every send is best-effort: billing state is already committed by the caller,
 * and a mail outage must never fail a webhook or a checkout verify — Razorpay
 * would retry and double-process. Failures are logged and swallowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingEmailService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final EmailSender emailSender;
    private final UserRepository userRepository;

    @Value("${app.mail.billing-from:payments@huevista.org}")
    private String billingFrom;

    // The dearest project rate — what an account with no plan pays. Read off the enum
    // rather than through PricingService: this class sits on the callback path of nearly
    // every billing service, and depending on one of them to phrase an e-mail is how a
    // cycle gets introduced later. Under-promising is the safe direction for a "here's
    // what your points are worth" line anyway — a reader on a plan gets more.
    private static final int BASE_PROJECT_POINTS = Plan.FREE.getExtraProjectPoints();

    /** First payment confirmed — the plan is live. */
    public void sendSubscriptionActivated(Subscription sub) {
        deliver(sub.getUser(), "Your HueVista " + planName(sub) + " plan is active",
                """
                Hi %s,

                Thank you — your payment was received and your HueVista %s plan is now active.

                Plan: %s (%s / month)
                Projects: %s per month (AI clean-up + AI wall detection on every one)
                Colour-board PDFs: %s downloads per month, up to %d images per PDF
                %s

                Razorpay will email you the tax invoice separately. Manage or cancel the plan
                any time from your dashboard.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub), planName(sub),
                        priceLine(sub.getPlan()), limitText(sub.getProjectsLimit()),
                        limitText(sub.getPdfDownloadsLimit()), sub.getPdfImageLimit(),
                        periodLine(sub)));
    }

    /** A renewal charge landed — quotas are refreshed. */
    public void sendSubscriptionRenewed(Subscription sub) {
        deliver(sub.getUser(), "Payment received — HueVista " + planName(sub) + " renewed",
                """
                Hi %s,

                Your HueVista %s plan has renewed and your monthly quotas are refreshed.

                Projects: %s per month
                Colour-board PDFs: %s downloads per month
                %s

                Razorpay will email you the tax invoice separately.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub),
                        limitText(sub.getProjectsLimit()),
                        limitText(sub.getPdfDownloadsLimit()), periodLine(sub)));
    }

    /** Razorpay could not collect the renewal — the subscription is halted. */
    public void sendPaymentFailed(Subscription sub) {
        deliver(sub.getUser(), "Action needed — HueVista payment failed",
                """
                Hi %s,

                We couldn't collect the renewal payment for your HueVista %s plan, so it is
                paused. New projects and PDF downloads stay off until payment succeeds.

                Please update your payment method from the Razorpay link in their email, or
                reply to this address and we'll help you sort it out.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub)));
    }

    /** The user asked to cancel — confirms when access ends. */
    public void sendCancellationScheduled(Subscription sub) {
        String until = sub.getCurrentPeriodEnd() != null
                ? "You keep full access until " + sub.getCurrentPeriodEnd().format(DATE) + "."
                : "You keep full access until the end of the current billing period.";
        deliver(sub.getUser(), "Your HueVista plan will not renew",
                """
                Hi %s,

                As requested, your HueVista %s plan is set to cancel at the end of the
                current billing period and will not be charged again. %s

                Changed your mind? Just subscribe again from the pricing page.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub), until));
    }

    /** The subscription has fully ended (gateway-confirmed cancellation). */
    public void sendSubscriptionEnded(Subscription sub) {
        deliver(sub.getUser(), "Your HueVista " + planName(sub) + " plan has ended",
                """
                Hi %s,

                Your HueVista %s plan has ended. Your projects and account are safe — only
                making new rooms and downloading colour boards are switched off.

                You can re-subscribe any time from the pricing page.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub)));
    }

    /** Extra-project purchase receipt. Paid in points, so no invoice follows. */
    public void sendProjectCreditPurchased(String userId, int points) {
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                "1 extra HueVista project added",
                """
                Hi %s,

                %d points have been spent and one extra project added to your account. It's
                ready to use right away.

                No invoice follows this one — points were paid for (or earned) when they were
                added, and spending them is not a fresh charge.

                — The HueVista team
                """.formatted(firstName(user), points)));
    }

    /**
     * Extra-project receipt for the CASH rail. Its own mail rather than a shared one,
     * because this is a real charge on a card: it names the amount and says an invoice
     * follows, neither of which is true of the points version above.
     */
    public void sendProjectPurchased(String userId, int amountPaise, int validDays) {
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                "Payment received — 1 extra HueVista project",
                """
                Hi %s,

                Thank you — your payment of Rs. %.2f was received and one extra project has
                been added to your account.

                It's ready to use right away. Once you turn it into a room it stays editable
                for %d days of use, and those days pause while a plan is covering your
                account. Extra projects can also be assigned to a customer from your shop
                portal, the same as the ones your plan includes.

                Razorpay will email you the tax invoice separately.

                — The HueVista team
                """.formatted(firstName(user), amountPaise / 100.0, validDays)));
    }

    /**
     * Reopen receipt, for both rails — {@code amountPaise} for a card payment,
     * {@code points} for the points one, and exactly one of them is ever non-zero.
     */
    public void sendProjectReopened(String userId, int points, int amountPaise, int validDays) {
        // Three rails, three true sentences. Neither figure set means the reopen was paid
        // for out of a project the account had already bought — the one case where nothing
        // at all moved today, and where "0 points have been spent" would be both wrong and
        // alarming.
        String paidWith = amountPaise > 0
                ? "your payment of Rs. %.2f was received".formatted(amountPaise / 100.0)
                : points > 0
                    ? "%d points have been spent".formatted(points)
                    : "one of the projects you had already bought has been used";
        String invoiceLine = amountPaise > 0
                ? "Razorpay will email you the tax invoice separately."
                : points > 0
                    ? "No invoice follows this one — points were paid for (or earned) when they "
                      + "were added, and spending them is not a fresh charge."
                    : "No invoice follows this one — nothing was charged today. The project was "
                      + "paid for when you bought it, and its receipt covers this.";
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                "HueVista project reopened for another " + validDays + " days",
                """
                Hi %s,

                Thank you — %s, and your project is open again for another %d days of use.

                Everything you had is still there: the cleaned photo, the walls and the
                colours you last applied. Pick up where you left off from your dashboard.

                %s

                — The HueVista team
                """.formatted(firstName(user), paidWith, validDays, invoiceLine)));
    }

    // ── Reward points ────────────────────────────────────────────────────────

    /** Receipt for a points purchase. Names the expiry date, because they do expire. */
    public void sendPointsPurchased(String userId, int points, int amountPaise, int validityDays) {
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                points + " HueVista points added",
                """
                Hi %s,

                Thank you — %d points have been added to your HueVista account for Rs. %.2f.

                %s

                Points last %d days from today, so these are good until %s. Spending always
                uses your oldest points first, so you never lose ones you could have used.

                Razorpay will email you the tax invoice separately.

                — The HueVista team
                """.formatted(firstName(user), points, amountPaise / 100.0,
                        whatPointsBuy(points), validityDays,
                        DATE.format(LocalDateTime.now().plusDays(validityDays)))));
    }

    /**
     * Points are a year old in {@code daysLeft} days. Says the number, the date and what
     * that many points would actually buy — "1,200 points expiring" means nothing to a
     * shop that has never counted in points.
     */
    public void sendPointsExpiringSoon(String userId, int points, LocalDateTime expiresAt, int daysLeft) {
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                points + " HueVista points expire in " + daysLeft + " days",
                """
                Hi %s,

                %d of your HueVista reward points expire on %s — that's %d days from now.

                Points last one year from the day you earn them, and these are the oldest
                batch on your account. Spending always uses the oldest points first, so
                anything you buy between now and then comes out of this batch.

                %s

                Spend them from the Points panel in your dashboard.

                — The HueVista team
                """.formatted(firstName(user), points, DATE.format(expiresAt), daysLeft,
                        whatPointsBuy(points))));
    }

    /** Last day — same facts, no softening. */
    public void sendPointsExpiringToday(String userId, int points, LocalDateTime expiresAt) {
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                points + " HueVista points expire today",
                """
                Hi %s,

                This is the last day to use %d of your HueVista reward points — they expire
                at the end of today, %s.

                %s

                Spend them from the Points panel in your dashboard. Points you don't use
                today are gone; the rest of your balance is unaffected.

                — The HueVista team
                """.formatted(firstName(user), points, DATE.format(expiresAt),
                        whatPointsBuy(points))));
    }

    /**
     * Turn a point total into the things it buys, so the number means something. Prices
     * are the point prices, not rupees — quoting rupees here would invite the shop to
     * treat points as cash, which they are not.
     */
    private String whatPointsBuy(int points) {
        int projects = points / BASE_PROJECT_POINTS;
        if (projects >= 1) {
            return "That's at least %d extra project%s — more if you're on a plan, since "
                    .formatted(projects, projects == 1 ? "" : "s")
                    + "projects cost fewer points the bigger your plan.";
        }
        return "On their own they won't cover a purchase yet, but they top up whatever you "
                + "buy next.";
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void deliver(User user, String subject, String body) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        try {
            emailSender.send(billingFrom, user.getEmail(), subject, body);
        } catch (Exception e) {
            log.warn("Billing email failed (ignored): to={} subject=\"{}\" error={}",
                    user.getEmail(), subject, e.getMessage());
        }
    }

    private static String firstName(User user) {
        String name = user != null ? user.getName() : null;
        if (name == null || name.isBlank()) return "there";
        return name.strip().split("\\s+")[0];
    }

    private static String planName(Subscription sub) {
        return sub.getPlan() != null ? sub.getPlan().getDisplayName() : "subscription";
    }

    private static String priceLine(Plan plan) {
        if (plan == null || plan.getPriceInPaise() < 0) return "custom pricing";
        return "Rs. " + String.format("%.0f", plan.priceInRupees());
    }

    private static String limitText(int limit) {
        return limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit);
    }

    private static String periodLine(Subscription sub) {
        LocalDateTime end = sub.getCurrentPeriodEnd();
        return end != null ? "Current period ends: " + end.format(DATE) : "";
    }
}
