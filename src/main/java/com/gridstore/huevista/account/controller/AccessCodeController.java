package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.dto.AccessCodeResponse;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.dto.RedeemCodeRequest;
import com.gridstore.huevista.account.service.AccessCodeService;
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
@Tag(name = "Customer Access Codes", description = "Retailer-issued temporary access codes for walk-in customers")
public class AccessCodeController {

    private final AccessCodeService accessCodeService;

    @Operation(summary = "Generate access code",
            description = "Generates a time-limited access code for walk-in customers. Only retailer org owners/managers can call this.")
    @PostMapping("/api/organizations/{orgId}/access-codes")
    public ResponseEntity<AccessCodeResponse> generateCode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @Valid @RequestBody GenerateAccessCodeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accessCodeService.generateCode(userDetails.getUsername(), orgId, request));
    }

    @Operation(summary = "List access codes", description = "Lists all access codes for an organization.")
    @GetMapping("/api/organizations/{orgId}/access-codes")
    public ResponseEntity<List<AccessCodeResponse>> listCodes(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(accessCodeService.listCodes(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Redeem access code",
            description = "Redeems a retailer-issued access code. Sets the calling user's role to CUSTOMER and links them to the retailer.")
    @PostMapping("/api/access-codes/redeem")
    public ResponseEntity<AccessCodeResponse> redeemCode(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody RedeemCodeRequest request) {
        return ResponseEntity.ok(accessCodeService.redeemCode(userDetails.getUsername(), request.getCode()));
    }
}
