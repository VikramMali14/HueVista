package com.gridstore.huevista.store.controller;

import com.gridstore.huevista.store.dto.StoreCheckoutResponse;
import com.gridstore.huevista.store.dto.StoreOrderResponse;
import com.gridstore.huevista.store.dto.StorePublicInfoResponse;
import com.gridstore.huevista.store.dto.VerifyStoreOrderRequest;
import com.gridstore.huevista.store.service.StoreKioskService;
import com.gridstore.huevista.store.service.StoreLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * PUBLIC kiosk endpoints — the walk-in customer's side of a retailer's store
 * link. No authentication: the slug is the capability to view/pay, and the
 * verify step's Razorpay signature is the proof of payment.
 */
@RestController
@RequestMapping("/api/store")
@RequiredArgsConstructor
@Tag(name = "Store Kiosk", description = "Public in-store kiosk: view a store link, pay, get a code + guest session")
public class StoreKioskController {

    private final StoreLinkService storeLinkService;
    private final StoreKioskService storeKioskService;

    @Operation(summary = "View a store link",
            description = "Public. The shop name and price for a kiosk page; 404 for an unknown slug.")
    @SecurityRequirements
    @GetMapping("/{slug}")
    public ResponseEntity<StorePublicInfoResponse> info(@PathVariable String slug) {
        return ResponseEntity.ok(storeLinkService.getPublicInfo(slug));
    }

    @Operation(summary = "Create a payment order for one image upload",
            description = "Public. Creates a Razorpay order for the link's price; the kiosk opens it in Checkout (UPI/QR).")
    @SecurityRequirements
    @PostMapping("/{slug}/order")
    public ResponseEntity<StoreOrderResponse> order(@PathVariable String slug) {
        return ResponseEntity.ok(storeKioskService.createOrder(slug));
    }

    @Operation(summary = "Verify the payment and start the guest session",
            description = "Public. Verifies the Checkout signature; on success issues the shop's access code "
                    + "(the customer's pickup code — the SHOP later redeems the chosen colours from it) and "
                    + "returns a guest token so the studio opens immediately. Idempotent per payment.")
    @SecurityRequirements
    @PostMapping("/{slug}/verify")
    public ResponseEntity<StoreCheckoutResponse> verify(
            @PathVariable String slug,
            @Valid @RequestBody VerifyStoreOrderRequest request) {
        return ResponseEntity.ok(storeKioskService.verifyAndIssue(slug, request));
    }
}
