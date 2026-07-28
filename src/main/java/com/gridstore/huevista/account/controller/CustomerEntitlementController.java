package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.account.dto.CustomerEntitlementResponse;
import com.gridstore.huevista.account.dto.GrantCodeProjectsRequest;
import com.gridstore.huevista.account.dto.ProjectGrantResponse;
import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.account.service.ProjectGrantService;
import jakarta.validation.Valid;
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
@RequiresFeature(AppFeature.CUSTOMER_PORTAL)
public class CustomerEntitlementController {

    private final CustomerEntitlementService entitlementService;
    private final ProjectGrantService grantService;

    @Operation(summary = "List a retailer's customers",
            description = "Lists the customers onboarded by this retailer org with their project allowance, usage, and access expiry. Owner/manager only.")
    @GetMapping("/api/organizations/{orgId}/customers")
    public ResponseEntity<List<CustomerEntitlementResponse>> listCustomers(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(entitlementService.listCustomers(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Give a customer more projects",
            description = "Raises a customer's project allowance. Each project reserves one image credit "
                    + "against the shop's plan — exactly like issuing a code — so an ACTIVE subscription "
                    + "is required (402 SUBSCRIPTION_REQUIRED without one). The grant is recorded and can "
                    + "be taken back while unused. Body is optional; defaults to one project.")
    @PostMapping("/api/organizations/{orgId}/customers/{customerId}/grant-project")
    public ResponseEntity<CustomerEntitlementResponse> grantProject(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String customerId,
            @Valid @RequestBody(required = false) GrantCodeProjectsRequest request) {
        int projects = request != null ? request.getProjects() : 1;
        return ResponseEntity.ok(entitlementService.grantExtraProjects(
                userDetails.getUsername(), orgId, customerId, projects));
    }

    @Operation(summary = "Projects this shop has given away",
            description = "Every grant the shop has made — to a customer directly or onto a code — newest "
                    + "first, each flagged with whether it can still be taken back.")
    @GetMapping("/api/organizations/{orgId}/project-grants")
    public ResponseEntity<List<ProjectGrantResponse>> listGrants(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(grantService.listForOrg(userDetails.getUsername(), orgId).stream()
                .map(g -> ProjectGrantResponse.from(g, grantService.isRevocable(g)))
                .toList());
    }

    @Operation(summary = "Take a grant back",
            description = "Returns the reserved images to the shop's quota and lowers the allowance. "
                    + "Refused once the customer has used the projects, and refused after the billing "
                    + "period that funded the grant has renewed — those images came out of that period.")
    @DeleteMapping("/api/organizations/{orgId}/project-grants/{grantId}")
    public ResponseEntity<ProjectGrantResponse> revokeGrant(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String grantId) {
        var grant = grantService.revoke(userDetails.getUsername(), orgId, grantId);
        return ResponseEntity.ok(ProjectGrantResponse.from(grant, false));
    }

    @Operation(summary = "Ask my shop for another project",
            description = "For a customer a shop onboarded: emails the shop's owner asking them to add "
                    + "a project. This is what a customer gets instead of a buy button — their projects "
                    + "were assigned and paid for by the shop, which can add another in one click.")
    @PostMapping("/api/me/request-more-projects")
    public ResponseEntity<Void> requestMoreProjects(@AuthenticationPrincipal UserDetails userDetails) {
        entitlementService.requestMoreProjects(userDetails.getUsername());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "My entitlement",
            description = "Returns the calling customer's project allowance, usage, and access expiry (null if they are not a customer).")
    @GetMapping("/api/me/entitlement")
    public ResponseEntity<CustomerEntitlementResponse> myEntitlement(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(entitlementService.getMyEntitlement(userDetails.getUsername()));
    }
}
