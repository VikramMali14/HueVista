package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.CustomerEntitlement;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CustomerEntitlementResponse {
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String retailerOrgId;
    private LocalDateTime accessExpiresAt;
    private boolean expired;
    private int projectAllowance;
    private int projectsCreated;
    private int projectsRemaining;
    private LocalDateTime updatedAt;

    public static CustomerEntitlementResponse from(CustomerEntitlement e) {
        return CustomerEntitlementResponse.builder()
                .customerId(e.getCustomer().getId())
                .customerName(e.getCustomer().getName())
                .customerEmail(e.getCustomer().getEmail())
                .retailerOrgId(e.getRetailerOrg() != null ? e.getRetailerOrg().getId() : null)
                .accessExpiresAt(e.getAccessExpiresAt())
                .expired(e.isExpired())
                .projectAllowance(e.getProjectAllowance())
                .projectsCreated(e.getProjectsCreated())
                .projectsRemaining(e.getProjectsRemaining())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
