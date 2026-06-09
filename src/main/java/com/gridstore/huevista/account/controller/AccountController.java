package com.gridstore.huevista.account.controller;

import com.gridstore.huevista.account.dto.*;
import com.gridstore.huevista.account.service.AccountService;
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
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Distributor and Retailer organization management")
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Create organization", description = "Creates a Distributor or Retailer organization owned by the calling user.")
    @PostMapping
    public ResponseEntity<OrgResponse> createOrganization(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateOrgRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createOrganization(userDetails.getUsername(), request));
    }

    @Operation(summary = "Get organization", description = "Returns organization details by ID. Caller must be a member.")
    @GetMapping("/{orgId}")
    public ResponseEntity<OrgResponse> getOrganization(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(accountService.getOrganization(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Get my organizations", description = "Returns all organizations the authenticated user belongs to.")
    @GetMapping("/mine")
    public ResponseEntity<List<OrgResponse>> getMyOrganizations(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(accountService.getMyOrganizations(userDetails.getUsername()));
    }

    @Operation(summary = "List members", description = "Returns all members of an organization. Caller must be a member.")
    @GetMapping("/{orgId}/members")
    public ResponseEntity<List<MemberResponse>> getMembers(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(accountService.getMembers(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Add member", description = "Adds a user to the organization. Requires OWNER role.")
    @PostMapping("/{orgId}/members")
    public ResponseEntity<MemberResponse> addMember(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.addMember(userDetails.getUsername(), orgId, request));
    }

    @Operation(summary = "Remove member", description = "Removes a user from the organization. Requires OWNER role.")
    @DeleteMapping("/{orgId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @PathVariable String userId) {
        accountService.removeMember(userDetails.getUsername(), orgId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Link retailer to distributor",
            description = "Links a retailer org under a distributor org. Requires OWNER/MANAGER role on the distributor.")
    @PostMapping("/{distributorOrgId}/retailers")
    public ResponseEntity<OrgResponse> linkRetailer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String distributorOrgId,
            @Valid @RequestBody LinkRetailerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.linkRetailer(userDetails.getUsername(), distributorOrgId, request));
    }

    @Operation(summary = "Get linked retailers", description = "Returns all retailer orgs linked under a distributor. Caller must be a member of the distributor.")
    @GetMapping("/{distributorOrgId}/retailers")
    public ResponseEntity<List<OrgResponse>> getLinkedRetailers(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String distributorOrgId) {
        return ResponseEntity.ok(accountService.getLinkedRetailers(userDetails.getUsername(), distributorOrgId));
    }

    @Operation(summary = "Get distributors for retailer", description = "Returns all distributor orgs that have linked this retailer. Caller must be a member of the retailer.")
    @GetMapping("/{retailerOrgId}/distributors")
    public ResponseEntity<List<OrgResponse>> getDistributors(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId) {
        return ResponseEntity.ok(accountService.getDistributorsForRetailer(userDetails.getUsername(), retailerOrgId));
    }
}
