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
                // An account auto-provisioned from an access code has no real e-mail — only
                // a synthetic one derived from the code. Publishing it made the counter (and
                // the customer themselves) read "ac-7kq2xr9m@customers.huevista.local" as a
                // contact address, which it is not, and which suggests an inbox somebody
                // could reach. The name the shop typed is the identity that matters here.
                .customerEmail(com.gridstore.huevista.auth.util.Emails.publicEmailOf(e.getCustomer()))
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
