package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
                .build();
    }
}
