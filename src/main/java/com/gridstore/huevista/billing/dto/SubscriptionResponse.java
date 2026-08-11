package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SubscriptionResponse {

    private String id;
    private Plan plan;
    private String planDisplayName;
    private SubscriptionStatus status;
    private String razorpaySubscriptionId;
    private String paymentUrl;
    // Present only on a freshly-created subscription so the browser can open the
    // in-app Razorpay Checkout for `razorpaySubscriptionId`. Null everywhere else.
    private String razorpayKeyId;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    /** How many of the plan this subscription is billed for — the multiplier behind
     *  {@code projectsLimit}. */
    private int quantity;
    // The project quota. One project covers the whole automatic pipeline — the AI photo
    // clean-up AND the AI wall detection — so it is charged once, not once per step.
    private int projectsUsed;
    private int projectsLimit;
    /** Projects still free to start: allowance less usage less what is held behind
     *  unredeemed access codes. */
    private int projectsRemaining;
    /** Projects held for access codes customers haven't redeemed yet. Already paid for,
     *  and excluded from {@code projectsRemaining} because they are spoken for. */
    private int reservedProjects;
    /** Extra projects bought at the plan's rate, still unused. Never expire; included in
     *  {@code projectsRemaining}. */
    private int purchasedProjectCredits;
    /** Projects carried over from a plan this one replaced. Included in
     *  {@code projectsRemaining}, but they expire when this cycle renews. */
    private int carriedProjectCredits;
    /** What one extra project costs on this plan — points, and money in paise. */
    private int extraProjectPoints;
    private int extraProjectPricePaise;
    /**
     * Whether this plan includes colour matching (the Colour finder). False on the free
     * tier, true on every paid one.
     *
     * Served rather than derived from {@code plan} in the client, because the client
     * deriving it means a hand-kept copy of which tiers include what — the kind of
     * duplicate that goes quietly wrong the day a tier changes.
     */
    private boolean colorMatching;
    private int pdfDownloadsUsed;
    private int pdfDownloadsLimit;
    private int pdfDownloadsRemaining;
    private int pdfImageLimit;
    private boolean cancelAtPeriodEnd;
    private boolean trial;
    /**
     * This account is exempt from billing entirely — there is no subscription behind
     * these numbers and never will be (see {@code UnbilledAccounts}).
     *
     * Distinct from {@code trial}, which is a real row on a real clock. An unbilled
     * account has no period, nothing to renew and nothing to cancel, so a client must
     * not offer it any of those; it exists so the UI can say "no subscription needed"
     * instead of rendering a plan card that cannot be acted on.
     */
    private boolean unbilled;
    private LocalDateTime createdAt;

    public static SubscriptionResponse from(Subscription sub) {
        return from(sub, null, null);
    }

    /**
     * The answer for an account the platform does not bill.
     *
     * Returned instead of the 404 an administrator used to get from
     * {@code /subscriptions/current} — an account with no subscription row asked for its
     * subscription, and "not found" is technically true and practically useless: it made
     * every client treat the person who administers the payments as an unpaid user, and
     * put a "your trial has ended" banner in the console.
     *
     * Not a granted ENTERPRISE row, which was the other way to fix it. A real row would
     * expire, renew, and turn up in revenue reports as a plan nobody paid for. This
     * carries the unlimited quotas the gates need while remaining, in every report that
     * counts money, exactly what it is: no subscription.
     */
    public static SubscriptionResponse unbilled() {
        return SubscriptionResponse.builder()
                .plan(Plan.ENTERPRISE)
                .planDisplayName("Platform administrator")
                .status(SubscriptionStatus.ACTIVE)
                .unbilled(true)
                .quantity(1)
                .projectsUsed(0)
                .projectsLimit(Integer.MAX_VALUE)
                .projectsRemaining(Integer.MAX_VALUE)
                .pdfDownloadsUsed(0)
                .pdfDownloadsLimit(Integer.MAX_VALUE)
                .pdfDownloadsRemaining(Integer.MAX_VALUE)
                // The per-document cap is a browser-memory guard rather than a
                // commercial limit, so it applies here like everywhere else.
                .pdfImageLimit(Plan.ENTERPRISE.getPdfImageLimit())
                .build();
    }

    public static SubscriptionResponse from(Subscription sub, String paymentUrl) {
        return from(sub, paymentUrl, null);
    }

    public static SubscriptionResponse from(Subscription sub, String paymentUrl, String razorpayKeyId) {
        int pdfRemaining = sub.getPdfDownloadsLimit() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(0, sub.getPdfDownloadsLimit() - sub.getPdfDownloadsUsed());
        // A free trial is not a paid plan, so it buys extras at the no-plan rate — the
        // dearest one. Reading the rate off the row's own plan would quote a trialing
        // shop the Starter discount it has not bought. (A free-tier row already names
        // FREE as its plan, so it lands on the same rate through the other branch:
        // 80 points, or ₹99.)
        Plan pricedAs = sub.isTrial() ? Plan.FREE : sub.getPlan();

        return SubscriptionResponse.builder()
                .id(sub.getId())
                .plan(sub.getPlan())
                .planDisplayName(sub.getPlan().getDisplayName())
                .status(sub.getStatus())
                .razorpaySubscriptionId(sub.getRazorpaySubscriptionId())
                .paymentUrl(paymentUrl)
                .razorpayKeyId(razorpayKeyId)
                .currentPeriodStart(sub.getCurrentPeriodStart())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .quantity(sub.getQuantity())
                .projectsUsed(sub.getProjectsUsed())
                .projectsLimit(sub.getProjectsLimit())
                .projectsRemaining(sub.projectsRemaining())
                .reservedProjects(sub.getReservedProjects())
                .purchasedProjectCredits(sub.getPurchasedProjectCredits())
                .carriedProjectCredits(sub.getCarriedProjectCredits())
                .extraProjectPoints(pricedAs.getExtraProjectPoints())
                .extraProjectPricePaise(pricedAs.extraProjectPriceWithTaxInPaise())
                // Read off the row's OWN plan, not `pricedAs`: a trial on a paid tier is
                // charged the no-plan rate for extras but is genuinely running that tier,
                // so it keeps the tools that come with it.
                .colorMatching(sub.getPlan().isColorMatching())
                .pdfDownloadsUsed(sub.getPdfDownloadsUsed())
                .pdfDownloadsLimit(sub.getPdfDownloadsLimit())
                .pdfDownloadsRemaining(pdfRemaining)
                .pdfImageLimit(sub.getPdfImageLimit())
                .cancelAtPeriodEnd(sub.isCancelAtPeriodEnd())
                .trial(sub.isTrial())
                .createdAt(sub.getCreatedAt())
                .build();
    }
}
