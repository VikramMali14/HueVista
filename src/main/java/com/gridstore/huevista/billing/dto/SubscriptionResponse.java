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
    // Image quota (the compulsory clean-up makes every image consume one).
    // Field names keep the historical "aiGenerations" naming for API compatibility.
    private int aiGenerationsUsed;
    private int aiGenerationsLimit;
    private int aiGenerationsRemaining;
    // AI auto-mask (wall-detection) quota — spent only when the shop picks the
    // automatic mask after clean-up. Limit 0 = plan is manual-masking only.
    private int autoMasksUsed;
    private int autoMasksLimit;
    private int autoMasksRemaining;
    /** Pay-per-image overage credits (Rs. 50 + GST each) still unused. Included in
     *  {@code aiGenerationsRemaining}. */
    private int purchasedImageCredits;
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
        // Remaining images include purchased pay-per-image overage credits.
        long allowance = (long) sub.getAiGenerationsLimit() + sub.getPurchasedImageCredits();
        int remaining = sub.getAiGenerationsLimit() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) Math.max(0, Math.min(Integer.MAX_VALUE, allowance - sub.getAiGenerationsUsed()));
        int autoMasksRemaining = sub.getAutoMasksLimit() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(0, sub.getAutoMasksLimit() - sub.getAutoMasksUsed());
        int pdfRemaining = sub.getPdfDownloadsLimit() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(0, sub.getPdfDownloadsLimit() - sub.getPdfDownloadsUsed());

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
                .aiGenerationsUsed(sub.getAiGenerationsUsed())
                .aiGenerationsLimit(sub.getAiGenerationsLimit())
                .aiGenerationsRemaining(remaining)
                .autoMasksUsed(sub.getAutoMasksUsed())
                .autoMasksLimit(sub.getAutoMasksLimit())
                .autoMasksRemaining(autoMasksRemaining)
                .purchasedImageCredits(sub.getPurchasedImageCredits())
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
