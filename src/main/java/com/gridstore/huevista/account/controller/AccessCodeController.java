package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.dto.AccessCodeResponse;
import com.gridstore.huevista.account.dto.AssignedProductsResponse;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.dto.GuestRedeemResponse;
import com.gridstore.huevista.account.dto.RedeemAccountResponse;
import com.gridstore.huevista.account.dto.RedeemCodeRequest;
import com.gridstore.huevista.account.service.AccessCodeService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
    private final ProjectService projectService;

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

    @Operation(summary = "Redeem access code as a guest",
            description = "Public. Redeems a code without an account and returns a guest token scoped to it. "
                    + "The guest can create a single project; the issuing shop sees it via the code.")
    @SecurityRequirements
    @PostMapping("/api/access-codes/redeem-guest")
    public ResponseEntity<GuestRedeemResponse> redeemAsGuest(@Valid @RequestBody RedeemCodeRequest request) {
        return ResponseEntity.ok(accessCodeService.redeemAsGuest(request.getCode()));
    }

    @Operation(summary = "Redeem access code (auto-create customer account)",
            description = "Public. Redeems a retailer-issued code with no login: auto-provisions a "
                    + "passwordless CUSTOMER account named as the retailer entered and returns a full "
                    + "session (access + refresh tokens). The customer lands on their dashboard with "
                    + "their assigned project quota and products.")
    @SecurityRequirements
    @PostMapping("/api/access-codes/redeem-account")
    public ResponseEntity<RedeemAccountResponse> redeemAsNewCustomer(@Valid @RequestBody RedeemCodeRequest request) {
        return ResponseEntity.ok(accessCodeService.redeemAsNewCustomer(request.getCode()));
    }

    @Operation(summary = "My assigned products",
            description = "For a redeemed customer: the whole companies and individual products the "
                    + "retailer unlocked on their access code.")
    @GetMapping("/api/me/assigned-products")
    public ResponseEntity<AssignedProductsResponse> getAssignedProducts(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(accessCodeService.getAssignedProducts(userDetails.getUsername()));
    }

    @Operation(summary = "View a guest's selections for a code",
            description = "Shop-only. Returns the guest project created against this code WITH real shade "
                    + "codes, so the counter can fulfil the order. Requires owner/manager of the issuing org.")
    @GetMapping("/api/access-codes/{codeId}/guest-project")
    public ResponseEntity<ProjectResponse> getGuestProjectForShop(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String codeId) {
        accessCodeService.requireManagedCode(userDetails.getUsername(), codeId);
        ProjectResponse project = projectService.getGuestProjectForShop(codeId);
        if (project == null) {
            throw new ResourceNotFoundException("No guest project created for this code yet.");
        }
        return ResponseEntity.ok(project);
    }
}
