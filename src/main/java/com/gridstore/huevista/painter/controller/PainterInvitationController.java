package com.gridstore.huevista.painter.controller;

import com.gridstore.huevista.painter.dto.GeneratePainterInvitationRequest;
import com.gridstore.huevista.painter.dto.PainterInvitationResponse;
import com.gridstore.huevista.painter.dto.PainterRetailerLinkResponse;
import com.gridstore.huevista.painter.dto.RedeemPainterInvitationRequest;
import com.gridstore.huevista.painter.service.PainterInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Painter Invitations", description = "Retailer-issued one-time codes for linking painters")
public class PainterInvitationController {

    private final PainterInvitationService invitationService;

    @Operation(summary = "Generate an invitation (retailer owner only)")
    @PostMapping("/api/organizations/{retailerOrgId}/painter-invitations")
    public ResponseEntity<PainterInvitationResponse> generate(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId,
            @Valid @RequestBody GeneratePainterInvitationRequest request) {
        PainterInvitationResponse resp = invitationService.generate(
                userDetails.getUsername(), retailerOrgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @Operation(summary = "List invitations for a retailer (owner only)")
    @GetMapping("/api/organizations/{retailerOrgId}/painter-invitations")
    public ResponseEntity<List<PainterInvitationResponse>> list(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId) {
        return ResponseEntity.ok(
                invitationService.listForRetailer(userDetails.getUsername(), retailerOrgId));
    }

    @Operation(summary = "Redeem an invitation", description = "Caller is upgraded to PAINTER role and linked to the retailer. Idempotent re-redeem returns 400.")
    @PostMapping("/api/painter-invitations/redeem")
    public ResponseEntity<PainterRetailerLinkResponse> redeem(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody RedeemPainterInvitationRequest request) {
        return ResponseEntity.ok(invitationService.redeem(userDetails.getUsername(), request.getCode()));
    }
}
