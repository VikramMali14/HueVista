package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.CustomerAccessCode;
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
    // Paint companies unlocked for this guest. Empty = all brands.
    private List<String> allowedBrands;

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
                .allowedBrands(c.getAllowedBrandList())
                .build();
    }
}
