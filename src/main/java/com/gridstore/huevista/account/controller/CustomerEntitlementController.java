package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.dto.CustomerEntitlementResponse;
import com.gridstore.huevista.account.service.CustomerEntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Customer Entitlements", description = "Per-customer project allowance + validity, managed by retailers")
public class CustomerEntitlementController {

    private final CustomerEntitlementService entitlementService;

    @Operation(summary = "List a retailer's customers",
            description = "Lists the customers onboarded by this retailer org with their project allowance, usage, and access expiry. Owner/manager only.")
    @GetMapping("/api/organizations/{orgId}/customers")
    public ResponseEntity<List<CustomerEntitlementResponse>> listCustomers(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(entitlementService.listCustomers(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Grant a customer one more project",
            description = "Increments a customer's project allowance by one. Owner/manager of the managing retailer org only.")
    @PostMapping("/api/organizations/{orgId}/customers/{customerId}/grant-project")
    public ResponseEntity<CustomerEntitlementResponse> grantProject(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String customerId) {
        return ResponseEntity.ok(entitlementService.grantExtraProject(userDetails.getUsername(), orgId, customerId));
    }

    @Operation(summary = "My entitlement",
            description = "Returns the calling customer's project allowance, usage, and access expiry (null if they are not a customer).")
    @GetMapping("/api/me/entitlement")
    public ResponseEntity<CustomerEntitlementResponse> myEntitlement(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(entitlementService.getMyEntitlement(userDetails.getUsername()));
    }
}
