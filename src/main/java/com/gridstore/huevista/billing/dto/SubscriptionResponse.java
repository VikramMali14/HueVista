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
    private int pdfDownloadsUsed;
    private int pdfDownloadsLimit;
    private int pdfDownloadsRemaining;
    private int pdfImageLimit;
    private boolean cancelAtPeriodEnd;
    private boolean trial;
    private LocalDateTime createdAt;

    public static SubscriptionResponse from(Subscription sub) {
        return from(sub, null, null);
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
        // shop the Starter discount it has not bought.
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
