package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.paint.dto.ShopProductResponse;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AccessCodeResponse {
    private String id;
    private String code;
    private String organizationId;
    private String organizationName;
    private int validDays;
    private LocalDateTime expiresAt;
    private boolean used;
    private boolean expired;
    // Cancelled by the shop before anyone redeemed it. A revoked code can never be
    // redeemed; its held image credits are already back in the shop's quota.
    private boolean revoked;
    private LocalDateTime revokedAt;
    // True while the code can still be cancelled or edited (nobody has redeemed it and
    // it has not been cancelled) — drives the shop's row actions.
    private boolean editable;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
    // The customer this code was issued to (retailer-entered).
    private String customerName;
    // Projects the customer may create with this code.
    private int projectQuota;
    // Rooms actually created against this code, and what is left of the quota. The
    // shop paid an image per assigned project, so it needs to see them counted down.
    // Populated by the service (the lightweight from() projection leaves them at 0).
    private int projectsUsed;
    private int projectsRemaining;
    // Paint companies unlocked for this customer. Empty = all brands.
    private List<String> allowedBrands;
    // Individual product ids unlocked (in addition to whole companies).
    private List<String> allowedProductIds;
    // Resolved individual products, populated by the service for list/detail views.
    // Null on the lightweight from() projection.
    private List<ShopProductResponse> assignedProducts;
    // When the shop last pushed this code's expiry out, and how often they have. Each
    // extension resets the window to a fresh 10 days, so a code never carries more than
    // 10 days ahead however many times it is renewed.
    private LocalDateTime extendedAt;
    private int extensionCount;
    // True while the shop can still top this code up — add projects or add 10 more days.
    // Unlike `editable` this survives redemption: topping up a code the customer is
    // actively using is the whole point. A cancelled code can never be topped up.
    private boolean topUpAllowed;

    public static AccessCodeResponse from(CustomerAccessCode c) {
        return AccessCodeResponse.builder()
                .id(c.getId())
                .code(c.getCode())
                .organizationId(c.getOrganization().getId())
                .organizationName(c.getOrganization().getName())
                .validDays(c.getValidDays())
                .expiresAt(c.getExpiresAt())
                .used(c.isUsed())
                .expired(c.isExpired())
                .revoked(c.isRevoked())
                .revokedAt(c.getRevokedAt())
                .editable(!c.isUsed() && !c.isRevoked())
                .usedAt(c.getUsedAt())
                .createdAt(c.getCreatedAt())
                .customerName(c.getCustomerName())
                .projectQuota(c.getProjectQuota())
                .projectsRemaining(c.getProjectQuota())
                .allowedBrands(c.getAllowedBrandList())
                .allowedProductIds(c.getAllowedProductIdList())
                .extendedAt(c.getExtendedAt())
                .extensionCount(c.getExtensionCount())
                .topUpAllowed(!c.isRevoked())
                .build();
    }

    /** Records how many rooms this code has produced, keeping `remaining` consistent. */
    public void applyProjectsUsed(int used) {
        this.projectsUsed = Math.max(0, used);
        this.projectsRemaining = Math.max(0, projectQuota - this.projectsUsed);
    }
}
