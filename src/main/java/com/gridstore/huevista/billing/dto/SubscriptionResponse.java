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
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private int aiGenerationsUsed;
    private int aiGenerationsLimit;
    private int aiGenerationsRemaining;
    private boolean cancelAtPeriodEnd;
    private LocalDateTime createdAt;

    public static SubscriptionResponse from(Subscription sub) {
        return from(sub, null);
    }

    public static SubscriptionResponse from(Subscription sub, String paymentUrl) {
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
                .currentPeriodStart(sub.getCurrentPeriodStart())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .aiGenerationsUsed(sub.getAiGenerationsUsed())
                .aiGenerationsLimit(sub.getAiGenerationsLimit())
                .aiGenerationsRemaining(remaining)
                .cancelAtPeriodEnd(sub.isCancelAtPeriodEnd())
                .createdAt(sub.getCreatedAt())
                .build();
    }
}
