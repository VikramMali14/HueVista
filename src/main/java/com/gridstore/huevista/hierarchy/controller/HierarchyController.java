package com.gridstore.huevista.hierarchy.controller;

import com.gridstore.huevista.auth.dto.AdminUserResponse;
import com.gridstore.huevista.auth.dto.CreatePainterRequest;
import com.gridstore.huevista.auth.dto.CreateRetailerRequest;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.hierarchy.dto.AssignBrandsRequest;
import com.gridstore.huevista.hierarchy.dto.AssignFeaturesRequest;
import com.gridstore.huevista.hierarchy.dto.MyAccessResponse;
import com.gridstore.huevista.hierarchy.dto.NetworkReportResponse;
import com.gridstore.huevista.hierarchy.dto.RetailerBrandOption;
import com.gridstore.huevista.hierarchy.dto.RetailerFeatureOption;
import com.gridstore.huevista.hierarchy.service.HierarchyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The account hierarchy: ADMIN → DISTRIBUTOR → RETAILER → PAINTER. Each level
 * provisions the next; /network reports the caller's downline as a tree.
 */
@RestController
@RequestMapping("/api/hierarchy")
@RequiredArgsConstructor
@Tag(name = "Hierarchy", description = "Admin → distributor → retailer → painter provisioning and network reports")
public class HierarchyController {

    private final HierarchyService hierarchyService;
    private final AuditService auditService;

    @Operation(summary = "Create a shop (retailer) account",
            description = "ADMIN or DISTRIBUTOR. Provisions a RETAILER user + organization + free trial; "
                    + "a distributor's new shop is auto-linked to their org.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @PostMapping("/retailers")
    public ResponseEntity<AdminUserResponse> createRetailer(
            @Valid @RequestBody CreateRetailerRequest request,
            Authentication auth) {
        AdminUserResponse created = hierarchyService.createRetailer(auth.getName(), request);
        auditService.record(auth.getName(), "RETAILER_CREATED", "USER", created.getId(),
                "shop account created via hierarchy");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Put a shop on the free tier",
            description = "ADMIN, or a DISTRIBUTOR for a shop in their own network. Grants the free tier "
                    + "(7 days, 3 projects: 2 AI-masked and 1 by hand). A no-op for a shop that already "
                    + "holds a live plan — a free tier must never supersede one they paid for.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @PostMapping("/retailers/{retailerUserId}/free-tier")
    public ResponseEntity<com.gridstore.huevista.billing.dto.SubscriptionResponse> grantFreeTier(
            @PathVariable String retailerUserId,
            Authentication auth) {
        var sub = hierarchyService.grantFreeTier(auth.getName(), retailerUserId);
        auditService.record(auth.getName(), "FREE_TIER_GRANTED", "USER", retailerUserId,
                "free tier assigned via hierarchy");
        return ResponseEntity.ok(sub);
    }

    @Operation(summary = "Create a painter account",
            description = "RETAILER only. Provisions a PAINTER user with a profile, already linked (ACTIVE) "
                    + "to the caller's shop.")
    @PreAuthorize("hasRole('RETAILER')")
    @com.gridstore.huevista.account.security.RequiresFeature(
            com.gridstore.huevista.account.model.AppFeature.NETWORK)
    @PostMapping("/painters")
    public ResponseEntity<AdminUserResponse> createPainter(
            @Valid @RequestBody CreatePainterRequest request,
            Authentication auth) {
        AdminUserResponse created = hierarchyService.createPainter(auth.getName(), request);
        auditService.record(auth.getName(), "PAINTER_CREATED", "USER", created.getId(),
                "painter account created by retailer");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Network report",
            description = "Role-scoped downline tree with rollup counts. ADMIN sees every distributor, "
                    + "retailer and painter; a DISTRIBUTOR sees their retailers (and those shops' painters); "
                    + "a RETAILER sees their painters.")
    // Gated for a RETAILER only — the guard resolves the CALLER's shop, so an admin or a
    // distributor (who is the one doing the granting) is never limited by it.
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR','RETAILER')")
    @com.gridstore.huevista.account.security.RequiresFeature(
            com.gridstore.huevista.account.model.AppFeature.NETWORK)
    @GetMapping("/network")
    public ResponseEntity<NetworkReportResponse> network(Authentication auth) {
        return ResponseEntity.ok(hierarchyService.network(auth.getName()));
    }

    @Operation(summary = "List a shop's brand assignments",
            description = "ADMIN or DISTRIBUTOR. Every paint brand with a flag for whether the shop currently "
                    + "has it assigned. Whether the shop is limited at all is reported by `brandsRestricted` "
                    + "on its node in /network — no rows can mean either 'unrestricted' or 'every brand "
                    + "revoked'.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @GetMapping("/retailers/{retailerOrgId}/brands")
    public ResponseEntity<List<RetailerBrandOption>> retailerBrands(
            @PathVariable String retailerOrgId, Authentication auth) {
        return ResponseEntity.ok(hierarchyService.retailerBrandOptions(auth.getName(), retailerOrgId));
    }

    @Operation(summary = "Set a shop's brand assignments",
            description = "ADMIN or DISTRIBUTOR. Replaces the shop's brand selection wholesale. "
                    + "`unrestricted` lifts the limit entirely; otherwise `brandIds` is the complete "
                    + "allowance and an empty list really does mean no brands at all — it does NOT "
                    + "clear the restriction.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @PutMapping("/retailers/{retailerOrgId}/brands")
    public ResponseEntity<List<RetailerBrandOption>> setRetailerBrands(
            @PathVariable String retailerOrgId,
            @Valid @RequestBody AssignBrandsRequest request,
            Authentication auth) {
        List<RetailerBrandOption> options =
                hierarchyService.assignBrands(auth.getName(), retailerOrgId, request.getBrandIds(), request.isUnrestricted());
        auditService.record(auth.getName(), "RETAILER_BRANDS_ASSIGNED", "ORGANIZATION", retailerOrgId,
                request.isUnrestricted() ? "unrestricted (all brands)"
                        : (request.getBrandIds() == null ? 0 : request.getBrandIds().size()) + " brands");
        return ResponseEntity.ok(options);
    }

    @Operation(summary = "List everything a distributor can grant",
            description = "ADMIN or DISTRIBUTOR. The paint companies and pages available to grant, "
                    + "with nothing assigned — what the shop-creation form fills its checklists from, "
                    + "before there is a shop to read a selection off.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @GetMapping("/grantable")
    public ResponseEntity<Map<String, Object>> grantable() {
        return ResponseEntity.ok(Map.of(
                "brands", hierarchyService.grantableBrands(),
                "features", hierarchyService.grantableFeatures()));
    }

    @Operation(summary = "List a shop's page access",
            description = "ADMIN or DISTRIBUTOR. Every grantable page (Studio, Colour finder, Catalogue, "
                    + "Products, Customer portal, My network) with a flag for whether the shop can open it. "
                    + "An unrestricted shop reports every page as assigned.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @GetMapping("/retailers/{retailerOrgId}/features")
    public ResponseEntity<List<RetailerFeatureOption>> retailerFeatures(
            @PathVariable String retailerOrgId, Authentication auth) {
        return ResponseEntity.ok(hierarchyService.retailerFeatureOptions(auth.getName(), retailerOrgId));
    }

    @Operation(summary = "Set a shop's page access",
            description = "ADMIN or DISTRIBUTOR. Replaces the shop's page selection wholesale. "
                    + "`unrestricted` lifts the limit entirely; otherwise `features` is the complete "
                    + "allowance and an empty list really does mean no optional pages. The dashboard, "
                    + "account and plan pages are never restrictable.")
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR')")
    @PutMapping("/retailers/{retailerOrgId}/features")
    public ResponseEntity<List<RetailerFeatureOption>> setRetailerFeatures(
            @PathVariable String retailerOrgId,
            @Valid @RequestBody AssignFeaturesRequest request,
            Authentication auth) {
        List<RetailerFeatureOption> options = hierarchyService.assignFeatures(
                auth.getName(), retailerOrgId, request.getFeatures(), request.isUnrestricted());
        auditService.record(auth.getName(), "RETAILER_FEATURES_ASSIGNED", "ORGANIZATION", retailerOrgId,
                request.isUnrestricted() ? "unrestricted (all pages)"
                        : (request.getFeatures() == null ? 0 : request.getFeatures().size()) + " pages");
        return ResponseEntity.ok(options);
    }

    @Operation(summary = "My access",
            description = "The signed-in caller's own brand and page allowances — what the app uses to "
                    + "decide which navigation tabs to render and which pages to admit. Non-retailers "
                    + "always come back unrestricted.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/my-access")
    public ResponseEntity<MyAccessResponse> myAccess(Authentication auth) {
        return ResponseEntity.ok(hierarchyService.myAccess(auth.getName()));
    }
}
