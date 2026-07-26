package com.gridstore.huevista.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform data reset — kept in its own controller because it is the single most
 * destructive endpoint in the product and deserves to be obvious in the API surface
 * rather than buried among the routine admin CRUD.
 */
@RestController
@RequestMapping("/api/admin/data-reset")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Super-admin endpoints — ROLE_ADMIN only")
public class AdminDataResetController {

    private final DataResetService dataResetService;

    /** Request body for the reset. The phrase is the only guard against a stray click. */
    public record ResetRequest(String confirmation) {}

    @Operation(
            summary = "Preview the platform data reset (admin)",
            description = """
                    Shows exactly what a reset would clear and what it would keep, with live row
                    counts, so the confirmation screen states real numbers rather than a promise.
                    Read-only.
                    """
    )
    @GetMapping
    public ResponseEntity<DataResetService.ResetResult> preview() {
        return ResponseEntity.ok(dataResetService.preview());
    }

    @Operation(
            summary = "Reset all platform data, keeping the paint catalogue (admin)",
            description = """
                    Empties every account, organization, project, region, subscription, wallet,
                    payment, access code and audit entry. **Keeps** brands, product lines and
                    shades — the shade catalogue is uploaded by hand and AI-enriched once, and
                    has no copy in the repository, so it is never included.

                    Your own admin account survives with its id intact, so you stay signed in.
                    Every other account is deleted. Irreversible: take a database snapshot first.

                    Requires `confirmation` to equal `RESET ALL DATA`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Tables cleared, rows removed, catalogue kept")
    @ApiResponse(responseCode = "400", description = "Confirmation phrase missing or wrong")
    @PostMapping
    public ResponseEntity<DataResetService.ResetResult> reset(Authentication auth,
                                                              @RequestBody ResetRequest request) {
        return ResponseEntity.ok(dataResetService.resetKeepingCatalogue(
                auth.getName(), request == null ? null : request.confirmation()));
    }
}
