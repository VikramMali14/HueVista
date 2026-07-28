package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.account.dto.AccessCodeResponse;
import com.gridstore.huevista.account.dto.AssignedProductsResponse;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.dto.GrantCodeProjectsRequest;
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
@RequiresFeature(AppFeature.CUSTOMER_PORTAL)
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

    @Operation(summary = "Cancel an unredeemed access code",
            description = "Cancels a code nobody has redeemed yet and returns its held image "
                    + "credits to the shop's monthly quota. Owners/managers of the issuing org "
                    + "only. A code that has already been redeemed cannot be cancelled.")
    @DeleteMapping("/api/organizations/{orgId}/access-codes/{codeId}")
    public ResponseEntity<AccessCodeResponse> revokeCode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String codeId) {
        return ResponseEntity.ok(accessCodeService.revokeCode(userDetails.getUsername(), orgId, codeId));
    }

    @Operation(summary = "Edit an unredeemed access code",
            description = "Updates the customer name and the companies / products a not-yet-redeemed "
                    + "code unlocks. The assigned project count is fixed once issued (it is backed by "
                    + "held image credits) — cancel and re-issue to change it.")
    @PutMapping("/api/organizations/{orgId}/access-codes/{codeId}")
    public ResponseEntity<AccessCodeResponse> updateCode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String codeId,
            @Valid @RequestBody GenerateAccessCodeRequest request) {
        return ResponseEntity.ok(accessCodeService.updateCode(userDetails.getUsername(), orgId, codeId, request));
    }

    @Operation(summary = "Add projects to a code already issued",
            description = "Tops up a code the customer already holds with more projects, so they don't "
                    + "need a second code. Works on redeemed codes — that is the point. Each added "
                    + "project reserves one image credit, so an ACTIVE subscription is required "
                    + "(402 SUBSCRIPTION_REQUIRED without one).")
    @PostMapping("/api/organizations/{orgId}/access-codes/{codeId}/projects")
    public ResponseEntity<AccessCodeResponse> grantExtraProjects(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String codeId,
            @Valid @RequestBody GrantCodeProjectsRequest request) {
        return ResponseEntity.ok(accessCodeService.grantExtraProjects(
                userDetails.getUsername(), orgId, codeId, request.getProjects()));
    }

    @Operation(summary = "Give a code another 10 days",
            description = "Resets the code's validity to a fresh 10 days from now (never more, however "
                    + "often it is renewed) and moves the redeeming customer's access window with it. "
                    + "Nothing is charged — the projects were already paid for. Requires an ACTIVE "
                    + "subscription (402 SUBSCRIPTION_REQUIRED without one).")
    @PostMapping("/api/organizations/{orgId}/access-codes/{codeId}/extend")
    public ResponseEntity<AccessCodeResponse> extendValidity(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String codeId) {
        return ResponseEntity.ok(accessCodeService.extendValidity(userDetails.getUsername(), orgId, codeId));
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

    @Operation(summary = "View every room created against a code",
            description = "Shop-only. Returns ALL projects created against this code WITH real shade "
                    + "codes, newest first — a retailer-assigned code can carry several projects. "
                    + "Requires owner/manager of the issuing org; empty list when nothing exists yet.")
    @GetMapping("/api/access-codes/{codeId}/projects")
    public ResponseEntity<List<ProjectResponse>> getProjectsForShop(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String codeId) {
        accessCodeService.requireManagedCode(userDetails.getUsername(), codeId);
        return ResponseEntity.ok(projectService.getProjectsForShop(codeId));
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
