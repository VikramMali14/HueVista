package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.CreateRetailerComboRequest;
import com.gridstore.huevista.paint.dto.RetailerComboResponse;
import com.gridstore.huevista.paint.service.RetailerComboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Retailer-curated shade combinations ("shop picks").
 *
 * Management lives under the org (owner/manager); the studio reads through
 * {@code /api/me/retailer-combos}, which resolves the shop from whoever is
 * asking — retailer staff, an entitled customer, or a guest access code.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Retailer combos", description = "A shop's suggested three-shade combinations")
public class RetailerComboController {

    private final RetailerComboService comboService;

    @Operation(summary = "List a shop's suggested combinations", description = "Caller must be an org member.")
    @GetMapping("/api/organizations/{orgId}/combos")
    public ResponseEntity<List<RetailerComboResponse>> list(@PathVariable String orgId, Authentication auth) {
        return ResponseEntity.ok(comboService.list(auth.getName(), orgId));
    }

    @Operation(summary = "Add a suggested combination",
            description = "Exactly three shades in main/accent/trim order. Owner or manager only.")
    @PostMapping("/api/organizations/{orgId}/combos")
    public ResponseEntity<RetailerComboResponse> create(
            @PathVariable String orgId,
            @Valid @RequestBody CreateRetailerComboRequest request,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(comboService.create(auth.getName(), orgId, request));
    }

    @Operation(summary = "Remove a suggested combination", description = "Owner or manager only.")
    @DeleteMapping("/api/organizations/{orgId}/combos/{comboId}")
    public ResponseEntity<Void> delete(
            @PathVariable String orgId, @PathVariable String comboId, Authentication auth) {
        comboService.delete(auth.getName(), orgId, comboId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "My shop's suggested combinations",
            description = "The combinations the studio should offer the caller: their own shop's (retailer "
                    + "staff), their retailer's (customers with a valid entitlement), or the code-issuing "
                    + "shop's (guests). Empty list when there is no shop to show.")
    @GetMapping("/api/me/retailer-combos")
    public ResponseEntity<List<RetailerComboResponse>> mine(Authentication auth) {
        boolean guest = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_GUEST"::equals);
        return ResponseEntity.ok(comboService.forPrincipal(auth.getName(), guest));
    }
}
