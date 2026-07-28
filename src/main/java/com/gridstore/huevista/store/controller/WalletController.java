package com.gridstore.huevista.store.controller;

import com.gridstore.huevista.store.dto.WalletSummaryResponse;
import com.gridstore.huevista.store.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Retailer Kiosk Points",
        description = "What the shop's kiosk sold and the reward points it earned")
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "Kiosk points statement",
            description = "Spendable point balance, lifetime points earned, and the recent kiosk "
                    + "sales that earned them. Points are spent on HueVista services — extra "
                    + "images, auto-masks, projects — through the billing wallet endpoints under "
                    + "/api/billing/wallet, and are never withdrawable as cash. Owners/managers only.")
    @GetMapping("/api/organizations/{orgId}/wallet")
    public ResponseEntity<WalletSummaryResponse> wallet(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(walletService.getWallet(userDetails.getUsername(), orgId));
    }
}
