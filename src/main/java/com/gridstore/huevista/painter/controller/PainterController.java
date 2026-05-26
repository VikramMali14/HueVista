package com.gridstore.huevista.painter.controller;

import com.gridstore.huevista.painter.dto.PainterProfileResponse;
import com.gridstore.huevista.painter.dto.PainterRetailerLinkResponse;
import com.gridstore.huevista.painter.dto.UpdatePainterProfileRequest;
import com.gridstore.huevista.painter.service.PainterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/painters")
@RequiredArgsConstructor
@Tag(name = "Painters", description = "Painter profile and retailer linkage")
public class PainterController {

    private final PainterService painterService;

    @Operation(summary = "Get my painter profile", description = "Returns the profile for the authenticated painter. 404 until an invitation has been redeemed.")
    @GetMapping("/me")
    public ResponseEntity<PainterProfileResponse> getMyProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(painterService.getMyProfile(userDetails.getUsername()));
    }

    @Operation(summary = "Update my painter profile", description = "Updates trade-specific fields for the authenticated painter.")
    @PutMapping("/me")
    public ResponseEntity<PainterProfileResponse> updateMyProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdatePainterProfileRequest request) {
        return ResponseEntity.ok(painterService.updateMyProfile(userDetails.getUsername(), request));
    }

    @Operation(summary = "List retailers I work with", description = "Returns active retailer relationships for the authenticated painter.")
    @GetMapping("/me/retailers")
    public ResponseEntity<List<PainterRetailerLinkResponse>> listMyRetailers(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(painterService.listMyRetailers(userDetails.getUsername()));
    }

    @Operation(summary = "List painters for a retailer", description = "Returns active, profile-bearing painters linked to a retailer org. Used by the retailer to pick an assignee.")
    @GetMapping("/by-retailer/{retailerOrgId}")
    public ResponseEntity<List<PainterProfileResponse>> listForRetailer(
            @PathVariable String retailerOrgId) {
        return ResponseEntity.ok(painterService.listActivePaintersForRetailer(retailerOrgId));
    }

    @Operation(summary = "Remove a painter from a retailer (owner only)")
    @DeleteMapping("/by-retailer/{retailerOrgId}/{painterUserId}")
    public ResponseEntity<Void> removePainter(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId,
            @PathVariable String painterUserId) {
        painterService.removePainterFromRetailer(userDetails.getUsername(), retailerOrgId, painterUserId);
        return ResponseEntity.noContent().build();
    }
}
