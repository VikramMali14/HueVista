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

    /** First payment confirmed — the plan is live. */
    public void sendSubscriptionActivated(Subscription sub) {
        deliver(sub.getUser(), "Your HueVista " + planName(sub) + " plan is active",
                """
                Hi %s,

                Thank you — your payment was received and your HueVista %s plan is now active.

                Plan: %s (%s / month)
                AI previews: %s per month
                Colour-board PDFs: %s downloads per month, up to %d images per PDF
                %s

                Razorpay will email you the tax invoice separately. Manage or cancel the plan
                any time from your dashboard.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub), planName(sub),
                        priceLine(sub.getPlan()), limitText(sub.getAiGenerationsLimit()),
                        limitText(sub.getPdfDownloadsLimit()), sub.getPdfImageLimit(),
                        periodLine(sub)));
    }

    /** A renewal charge landed — quotas are refreshed. */
    public void sendSubscriptionRenewed(Subscription sub) {
        deliver(sub.getUser(), "Payment received — HueVista " + planName(sub) + " renewed",
                """
                Hi %s,

                Your HueVista %s plan has renewed and your monthly quotas are refreshed.

                AI previews: %s per month
                Colour-board PDFs: %s downloads per month
                %s

                Razorpay will email you the tax invoice separately.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub),
                        limitText(sub.getAiGenerationsLimit()),
                        limitText(sub.getPdfDownloadsLimit()), periodLine(sub)));
    }

    /** Razorpay could not collect the renewal — the subscription is halted. */
    public void sendPaymentFailed(Subscription sub) {
        deliver(sub.getUser(), "Action needed — HueVista payment failed",
                """
                Hi %s,

                We couldn't collect the renewal payment for your HueVista %s plan, so it is
                paused. AI previews and PDF downloads stay off until payment succeeds.

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
                the plan's AI previews and PDF downloads are off.

                You can re-subscribe any time from the pricing page.

                — The HueVista team
                """.formatted(firstName(sub.getUser()), planName(sub)));
    }

    /** One-time extra-project purchase receipt. */
    public void sendProjectCreditPurchased(String userId, int amountPaise) {
        userRepository.findById(userId).ifPresent(user -> deliver(user,
                "Payment received — 1 extra HueVista project",
                """
                Hi %s,

                Thank you — your payment of Rs. %.2f was received and one extra project has
                been added to your account. It's ready to use right away.

                Razorpay will email you the tax invoice separately.

                — The HueVista team
                """.formatted(firstName(user), amountPaise / 100.0)));
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
