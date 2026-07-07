package com.gridstore.huevista.store.controller;

import com.gridstore.huevista.store.dto.RequestRedemptionRequest;
import com.gridstore.huevista.store.dto.WalletRedemptionResponse;
import com.gridstore.huevista.store.dto.WalletSummaryResponse;
import com.gridstore.huevista.store.service.WalletService;
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
@Tag(name = "Retailer Wallet", description = "Kiosk earnings wallet: balance, activity, manual UPI payout requests")
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "Wallet summary",
            description = "Balance (kiosk shares earned minus non-rejected redemptions), recent kiosk payments "
                    + "and the redemption history. Owners/managers only.")
    @GetMapping("/api/organizations/{orgId}/wallet")
    public ResponseEntity<WalletSummaryResponse> wallet(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(walletService.getWallet(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Request a payout",
            description = "Holds the amount from the balance, emails the redemption inbox with the shop's UPI id, "
                    + "and queues the request for a manual admin decision.")
    @PostMapping("/api/organizations/{orgId}/wallet/redemptions")
    public ResponseEntity<WalletRedemptionResponse> requestRedemption(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @Valid @RequestBody RequestRedemptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(walletService.requestRedemption(userDetails.getUsername(), orgId, request));
    }

    @Operation(summary = "List this shop's redemption requests")
    @GetMapping("/api/organizations/{orgId}/wallet/redemptions")
    public ResponseEntity<List<WalletRedemptionResponse>> listRedemptions(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(walletService.listRedemptions(userDetails.getUsername(), orgId));
    }
}
