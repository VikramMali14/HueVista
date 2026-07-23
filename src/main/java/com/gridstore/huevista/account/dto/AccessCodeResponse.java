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
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
    // The customer this code was issued to (retailer-entered).
    private String customerName;
    // Projects the customer may create with this code.
    private int projectQuota;
    // Paint companies unlocked for this customer. Empty = all brands.
    private List<String> allowedBrands;
    // Individual product ids unlocked (in addition to whole companies).
    private List<String> allowedProductIds;
    // Resolved individual products, populated by the service for list/detail views.
    // Null on the lightweight from() projection.
    private List<ShopProductResponse> assignedProducts;

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
                .usedAt(c.getUsedAt())
                .createdAt(c.getCreatedAt())
                .customerName(c.getCustomerName())
                .projectQuota(c.getProjectQuota())
                .allowedBrands(c.getAllowedBrandList())
                .allowedProductIds(c.getAllowedProductIdList())
                .build();
    }
}
