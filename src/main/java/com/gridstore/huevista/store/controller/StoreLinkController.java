package com.gridstore.huevista.store.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.store.dto.CreateStoreLinkRequest;
import com.gridstore.huevista.store.dto.StoreLinkResponse;
import com.gridstore.huevista.store.dto.UpdateStoreLinkRequest;
import com.gridstore.huevista.store.service.StoreLinkService;
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
@Tag(name = "Store Links", description = "Retailer-managed public kiosk links (price per image, min Rs.50)")
@RequiresFeature(AppFeature.CUSTOMER_PORTAL)
public class StoreLinkController {

    private final StoreLinkService storeLinkService;

    @Operation(summary = "Create a store link",
            description = "Publishes a permanent public kiosk URL for this shop. Nothing is "
                    + "configured on it: the price is the platform's and the code window a "
                    + "walk-in buys is a platform default. Only retailer org owners/managers "
                    + "can call this.")
    @PostMapping("/api/organizations/{orgId}/store-links")
    public ResponseEntity<StoreLinkResponse> create(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId,
            @Valid @RequestBody CreateStoreLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(storeLinkService.createLink(userDetails.getUsername(), orgId, request));
    }

    @Operation(summary = "List store links")
    @GetMapping("/api/organizations/{orgId}/store-links")
    public ResponseEntity<List<StoreLinkResponse>> list(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String orgId) {
        return ResponseEntity.ok(storeLinkService.listLinks(userDetails.getUsername(), orgId));
    }

    @Operation(summary = "Pause or resume a store link",
            description = "Pausing stops new orders and keeps the printed URL working for when "
                    + "the shop resumes. Neither the price nor the code window is the shop's to set.")
    @PatchMapping("/api/store-links/{linkId}")
    public ResponseEntity<StoreLinkResponse> update(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String linkId,
            @Valid @RequestBody UpdateStoreLinkRequest request) {
        return ResponseEntity.ok(storeLinkService.updateLink(userDetails.getUsername(), linkId, request));
    }

    @Operation(summary = "Delete a store link",
            description = "Retires the link: its URL stops working immediately and it leaves the "
                    + "shop's list. The sales it made are kept — they are the shop's own history "
                    + "and the audit behind its points — and walk-ins who already bought through "
                    + "it keep the codes they paid for.")
    @DeleteMapping("/api/store-links/{linkId}")
    public ResponseEntity<StoreLinkResponse> delete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String linkId) {
        return ResponseEntity.ok(storeLinkService.deleteLink(userDetails.getUsername(), linkId));
    }
}
