package com.gridstore.huevista.store.controller;

import com.gridstore.huevista.store.dto.RedemptionDecisionRequest;
import com.gridstore.huevista.store.dto.WalletRedemptionResponse;
import com.gridstore.huevista.store.model.WalletRedemptionStatus;
import com.gridstore.huevista.store.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin side of the manual payout loop: review the queue the redemption inbox
 * email points at, then approve (after actually paying the UPI id) or reject.
 */
@RestController
@RequestMapping("/api/admin/wallet")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Wallet", description = "Wallet redemption queue — ROLE_ADMIN only")
public class AdminWalletController {

    private final WalletService walletService;

    @Operation(summary = "List redemption requests",
            description = "All requests (newest first), optionally filtered by status. PENDING is the work queue.")
    @GetMapping("/redemptions")
    public ResponseEntity<List<WalletRedemptionResponse>> list(
            @Parameter(description = "PENDING, APPROVED or REJECTED; omit for all")
            @RequestParam(required = false) WalletRedemptionStatus status) {
        return ResponseEntity.ok(walletService.adminListRedemptions(status));
    }

    @Operation(summary = "Approve or reject a redemption",
            description = "Approve only AFTER sending the money to the request's UPI id — approval records the "
                    + "payout as done. Rejecting returns the amount to the shop's balance.")
    @PostMapping("/redemptions/{redemptionId}/decision")
    public ResponseEntity<WalletRedemptionResponse> decide(
            @PathVariable String redemptionId,
            @Valid @RequestBody RedemptionDecisionRequest request,
            Authentication auth) {
        return ResponseEntity.ok(walletService.decideRedemption(
                auth.getName(), redemptionId, request.getApprove(), request.getNote()));
    }
}
