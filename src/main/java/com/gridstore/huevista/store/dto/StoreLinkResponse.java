package com.gridstore.huevista.store.dto;

import com.gridstore.huevista.store.model.StoreLink;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class StoreLinkResponse {
    private String id;
    private String slug;
    private String organizationId;
    private String organizationName;
    private int pricePaise;
    private String currency;
    private int validDays;
    private boolean active;
    private LocalDateTime createdAt;

    // ─── Pricing that actually applies today ─────────────────────────────────
    // The kiosk never closes when a shop's plan ends — only the platform's cut rises,
    // and with it the floor under the customer-facing price. All four numbers are
    // reported so the shop can see the change coming rather than meeting it in a
    // settlement: what they'll be charged now, and what it becomes at the other end.
    /** Whether the shop's plan is live right now. */
    private boolean subscriptionActive;
    /** The platform's cut per order today. */
    private int platformBasePaise;
    /** That cut with a live plan, and without one. */
    private int platformBaseSubscribedPaise;
    private int platformBaseLapsedPaise;
    /** What a walk-in is actually charged: the shop's price or the base, whichever is higher. */
    private int effectivePricePaise;

    public static StoreLinkResponse from(StoreLink link) {
        return StoreLinkResponse.builder()
                .id(link.getId())
                .slug(link.getSlug())
                .organizationId(link.getOrganization().getId())
                .organizationName(link.getOrganization().getName())
                .pricePaise(link.getPricePaise())
                .currency("INR")
                .validDays(link.getValidDays())
                .active(link.isActive())
                .createdAt(link.getCreatedAt())
                .build();
    }

    public StoreLinkResponse withPlatformBase(boolean subscriptionActive, int basePaise,
                                              int subscribedBasePaise, int lapsedBasePaise,
                                              int effectivePricePaise) {
        this.subscriptionActive = subscriptionActive;
        this.platformBasePaise = basePaise;
        this.platformBaseSubscribedPaise = subscribedBasePaise;
        this.platformBaseLapsedPaise = lapsedBasePaise;
        this.effectivePricePaise = effectivePricePaise;
        return this;
    }
}
