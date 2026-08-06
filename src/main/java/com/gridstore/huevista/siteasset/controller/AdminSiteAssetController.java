package com.gridstore.huevista.siteasset.controller;

import com.gridstore.huevista.siteasset.dto.SiteAssetResponse;
import com.gridstore.huevista.siteasset.service.SiteAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Putting pictures on the marketing site without a deploy.
 *
 * ADMIN only, via the {@code /api/admin/**} rule in SecurityConfig. The console
 * offers a fixed set of slots, each one a position the front end knows how to
 * draw; this endpoint only checks that the id it is given has the shape of a
 * slot, because the registry of which slots exist belongs with the markup that
 * renders them.
 */
@RestController
@RequestMapping("/api/admin/site-assets")
@RequiredArgsConstructor
@Tag(name = "Admin · site assets", description = "Upload the marketing site's images")
public class AdminSiteAssetController {

    private final SiteAssetService siteAssetService;

    @Operation(summary = "Every filled slot", description = "The same list the public manifest serves, for the console.")
    @ApiResponse(responseCode = "200", description = "Filled slots")
    @GetMapping
    public ResponseEntity<List<SiteAssetResponse>> list() {
        return ResponseEntity.ok(siteAssetService.list());
    }

    @Operation(
            summary = "Put an image in a slot",
            description = """
                    Replaces whatever the slot held. JPEG, PNG or WebP up to 8 MB.

                    No room/exterior classification runs here — that check belongs to the
                    painting pipeline and would reject most of what a marketing page needs.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Slot updated"),
            @ApiResponse(responseCode = "422", description = "Not a valid image, too large, or a malformed slot id",
                    content = @Content)
    })
    @PostMapping(value = "/{slot}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SiteAssetResponse> put(
            @Parameter(description = "Slot id, e.g. home.compare.before") @PathVariable String slot,
            @Parameter(description = "JPEG, PNG or WebP, max 8 MB") @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(siteAssetService.put(slot, file, userDetails.getUsername()));
    }

    @Operation(
            summary = "Empty a slot",
            description = "Puts the front end's built-in default back on the page. Succeeds when the slot is already empty."
    )
    @ApiResponse(responseCode = "204", description = "Slot is empty")
    @DeleteMapping("/{slot}")
    public ResponseEntity<Void> clear(
            @PathVariable String slot,
            @AuthenticationPrincipal UserDetails userDetails) {
        siteAssetService.clear(slot, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
