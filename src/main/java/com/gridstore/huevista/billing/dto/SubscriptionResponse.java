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
    private int aiGenerationsUsed;
    private int aiGenerationsLimit;
    private int aiGenerationsRemaining;
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
        int remaining = sub.getAiGenerationsLimit() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(0, sub.getAiGenerationsLimit() - sub.getAiGenerationsUsed());

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
                .cancelAtPeriodEnd(sub.isCancelAtPeriodEnd())
                .trial(sub.isTrial())
                .createdAt(sub.getCreatedAt())
                .build();
    }
}
