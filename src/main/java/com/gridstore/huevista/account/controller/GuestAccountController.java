package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.dto.GuestMergeResponse;
import com.gridstore.huevista.account.dto.MergeGuestAccountRequest;
import com.gridstore.huevista.account.service.GuestAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claiming a kiosk purchase onto the account the customer actually keeps.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Guest Accounts", description = "Folding an unclaimed kiosk account into a real one")
public class GuestAccountController {

    private final GuestAccountService guestAccountService;

    @Operation(summary = "Move a kiosk room into this account",
            description = "Moves everything the kiosk guest account holds — rooms, photos, project "
                    + "allowance, credits and points — onto the signed-in customer account, then "
                    + "retires the kiosk account. Authorised by the kiosk account's own session "
                    + "token, which only the browser that made the purchase has. One-way: "
                    + "afterwards the kiosk account is closed and its address is free to register.")
    @PostMapping("/api/me/merge-guest-account")
    public ResponseEntity<GuestMergeResponse> merge(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody MergeGuestAccountRequest request) {
        return ResponseEntity.ok(guestAccountService.mergeUsingGuestToken(
                userDetails.getUsername(), request.getGuestToken()));
    }
}
