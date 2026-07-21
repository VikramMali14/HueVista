package com.gridstore.huevista.hierarchy.controller;

import com.gridstore.huevista.auth.dto.AdminUserResponse;
import com.gridstore.huevista.auth.dto.CreatePainterRequest;
import com.gridstore.huevista.auth.dto.CreateRetailerRequest;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.hierarchy.dto.NetworkReportResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "Create a painter account",
            description = "RETAILER only. Provisions a PAINTER user with a profile, already linked (ACTIVE) "
                    + "to the caller's shop.")
    @PreAuthorize("hasRole('RETAILER')")
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
    @PreAuthorize("hasAnyRole('ADMIN','DISTRIBUTOR','RETAILER')")
    @GetMapping("/network")
    public ResponseEntity<NetworkReportResponse> network(Authentication auth) {
        return ResponseEntity.ok(hierarchyService.network(auth.getName()));
    }
}
