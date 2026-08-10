package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.dto.SetVisibleBrandsRequest;
import com.gridstore.huevista.account.dto.ShopBrandVisibilityResponse;
import com.gridstore.huevista.account.service.BrandAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * The shop's own answer to "which paint companies do we put in front of people?".
 *
 * Separate from the distributor's assignment endpoints on {@code HierarchyController},
 * and deliberately so — that is a grant handed DOWN to the shop, this is the shop's own
 * storefront decision, and the two are answered by different people at different times.
 * The distributor decides what a shop may carry; the shop decides what it stocks.
 *
 * <p>One setting, applied everywhere: the studio at the counter, the kiosk link, every
 * access code the shop issues and every customer it onboards all read the effective
 * catalogue through {@code BrandAccessService}, so a change here lands in all of them at
 * once rather than needing to be remembered at each surface.
 */
@RestController
@RequestMapping("/api/organizations/{retailerOrgId}/visible-brands")
@RequiredArgsConstructor
@Tag(name = "Shop settings", description = "A shop's own catalogue and display settings")
public class ShopBrandVisibilityController {

    private final BrandAccessService brandAccessService;

    @Operation(summary = "What this shop shows",
            description = "Every paint company the shop's distributor has granted it, each flagged "
                    + "with whether the shop currently shows it. The option list is the GRANT, not "
                    + "the platform catalogue — a company the shop was never assigned cannot be "
                    + "shown, so offering it as a checkbox would be a control that does nothing. "
                    + "Owner, manager, or an admin.")
    @GetMapping
    public ResponseEntity<ShopBrandVisibilityResponse> getVisibleBrands(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId) {
        return ResponseEntity.ok(
                brandAccessService.visibilityFor(userDetails.getUsername(), retailerOrgId));
    }

    @Operation(summary = "Set what this shop shows",
            description = "Replaces the selection wholesale, and applies immediately everywhere the "
                    + "shop's catalogue is read — the counter's studio, the kiosk, its access codes "
                    + "and its customers. `showAll` lifts the shop's own limit; otherwise `brandIds` "
                    + "IS the selection, and an empty one means no companies at all rather than a "
                    + "reset. Ids the distributor has not granted are ignored rather than rejected, "
                    + "so a revoke that lands mid-edit does not 400 the whole save. Owner, manager, "
                    + "or an admin.")
    @PutMapping
    public ResponseEntity<ShopBrandVisibilityResponse> setVisibleBrands(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId,
            @Valid @RequestBody SetVisibleBrandsRequest request) {
        return ResponseEntity.ok(
                brandAccessService.setVisibility(userDetails.getUsername(), retailerOrgId, request));
    }
}
