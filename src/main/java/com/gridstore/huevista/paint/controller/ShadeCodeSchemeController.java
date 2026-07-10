package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.ShadeCodeSchemeResponse;
import com.gridstore.huevista.paint.dto.UpdateShadeCodeSchemeRequest;
import com.gridstore.huevista.paint.service.ShadeCodeSchemeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

/**
 * A shop's shade-code scheme ("customer codes").
 *
 * One pattern replaces per-shade custom codes: customer code =
 * {@code PREFIX + code[0..2] + INFIX + code[2..] + SUFFIX}. Management lives
 * under the org (owner/manager); the studio reads through
 * {@code /api/me/shade-code-scheme}, which resolves the shop from whoever is
 * asking — retailer staff, an entitled customer, or a guest access code.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Shade-code scheme", description = "A shop's pattern for customer-facing shade codes")
public class ShadeCodeSchemeController {

    private final ShadeCodeSchemeService schemeService;

    @Operation(summary = "Get the shop's shade-code scheme",
            description = "All parts empty when the shop has no scheme. Caller must be an org member.")
    @GetMapping("/api/organizations/{orgId}/shade-code-scheme")
    public ResponseEntity<ShadeCodeSchemeResponse> get(@PathVariable String orgId, Authentication auth) {
        return ResponseEntity.ok(schemeService.get(auth.getName(), orgId));
    }

    @Operation(summary = "Set or clear the shop's shade-code scheme",
            description = "Prefix (max 4), inserted pair (max 2, goes after the first two characters of the "
                    + "shade code) and suffix (max 4); letters and digits only, stored uppercase. Sending all "
                    + "three empty clears the scheme. Owner or manager only.")
    @PutMapping("/api/organizations/{orgId}/shade-code-scheme")
    public ResponseEntity<ShadeCodeSchemeResponse> update(
            @PathVariable String orgId,
            @Valid @RequestBody UpdateShadeCodeSchemeRequest request,
            Authentication auth) {
        return ResponseEntity.ok(schemeService.update(auth.getName(), orgId, request));
    }

    @Operation(summary = "My shop's shade-code scheme",
            description = "The scheme the studio should encode shade codes with for the caller: their own "
                    + "shop's (retailer staff), their retailer's (entitled customers), or the code-issuing "
                    + "shop's (guests). All parts empty when there is no shop or no scheme.")
    @GetMapping("/api/me/shade-code-scheme")
    public ResponseEntity<ShadeCodeSchemeResponse> mine(Authentication auth) {
        boolean guest = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_GUEST"::equals);
        return ResponseEntity.ok(schemeService.forPrincipal(auth.getName(), guest));
    }
}
