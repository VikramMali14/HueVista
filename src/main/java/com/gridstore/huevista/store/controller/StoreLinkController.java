package com.gridstore.huevista.store.controller;

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
public class StoreLinkController {

    private final StoreLinkService storeLinkService;

    @Operation(summary = "Create a store link",
            description = "Publishes a public kiosk URL for this shop at the given price per image. "
                    + "Only retailer org owners/managers can call this.")
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

    @Operation(summary = "Update a store link", description = "Change the price or validity, or pause/resume the kiosk.")
    @PatchMapping("/api/store-links/{linkId}")
    public ResponseEntity<StoreLinkResponse> update(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String linkId,
            @Valid @RequestBody UpdateStoreLinkRequest request) {
        return ResponseEntity.ok(storeLinkService.updateLink(userDetails.getUsername(), linkId, request));
    }
}
