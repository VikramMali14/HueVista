package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class OrgResponse {
    private String id;
    private String name;
    private String slug;
    private OrgType type;
    private String ownerUserId;
    private String ownerName;
    private BigDecimal commissionRate;
    private boolean whitelabelEnabled;
    private String subdomainSlug;
    private LocalDateTime createdAt;

    public static OrgResponse from(Organization org) {
        return OrgResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .type(org.getType())
                .ownerUserId(org.getOwner().getId())
                .ownerName(org.getOwner().getName())
                .commissionRate(org.getCommissionRate())
                .whitelabelEnabled(org.isWhitelabelEnabled())
                .subdomainSlug(org.getSubdomainSlug())
                .createdAt(org.getCreatedAt())
                .build();
    }
}
