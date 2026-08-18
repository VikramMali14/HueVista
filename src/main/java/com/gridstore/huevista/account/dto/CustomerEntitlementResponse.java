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
    /**
     * The customer's address, or null when there isn't an honest one to show.
     *
     * Read through {@link com.gridstore.huevista.auth.util.Emails#publicEmailOf} rather
     * than off the row. A walk-in the kiosk opened an account for may have no reachable
     * address, in which case the stored one is synthesised from their access code purely
     * to key the row — {@code ac-7kq2xr9m@customers.huevista.local}. Printing that in a
     * shop's customer list offers the counter somewhere to write that does not exist.
     */
    private String customerEmail;
    private String retailerOrgId;
    private int projectAllowance;
    private int projectsCreated;
    private int projectsRemaining;
    private LocalDateTime updatedAt;

    public static CustomerEntitlementResponse from(CustomerEntitlement e) {
        return CustomerEntitlementResponse.builder()
                .customerId(e.getCustomer().getId())
                .customerName(e.getCustomer().getName())
                .customerEmail(com.gridstore.huevista.auth.util.Emails
                        .publicEmailOf(e.getCustomer()))
                .retailerOrgId(e.getRetailerOrg() != null ? e.getRetailerOrg().getId() : null)
                .projectAllowance(e.getProjectAllowance())
                .projectsCreated(e.getProjectsCreated())
                .projectsRemaining(e.getProjectsRemaining())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
