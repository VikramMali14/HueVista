package com.gridstore.huevista.store.controller;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.store.dto.KioskReentryConfirmRequest;
import com.gridstore.huevista.store.dto.KioskReentryRequest;
import com.gridstore.huevista.store.dto.KioskReentryStatusResponse;
import com.gridstore.huevista.store.dto.StoreCheckoutResponse;
import com.gridstore.huevista.store.dto.StoreOrderResponse;
import com.gridstore.huevista.store.dto.StorePublicInfoResponse;
import com.gridstore.huevista.store.dto.VerifyStoreOrderRequest;
import com.gridstore.huevista.store.service.KioskReentryService;
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
    private final KioskReentryService kioskReentryService;
    private final com.gridstore.huevista.billing.service.PaymentAttemptService paymentAttemptService;

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

    @Operation(summary = "Verify the payment and open the customer's studio",
            description = "Public. Verifies the Checkout signature; on success issues the shop's access code "
                    + "(the customer's pickup code — the SHOP later reads the chosen colours from it), "
                    + "opens or reuses the account the purchase belongs to, and returns a live session so "
                    + "the studio opens immediately. Idempotent per payment: a replay returns the same code "
                    + "and a fresh session on the same account.")
    @SecurityRequirements
    @PostMapping("/{slug}/verify")
    public ResponseEntity<StoreCheckoutResponse> verify(
            @PathVariable String slug,
            @Valid @RequestBody VerifyStoreOrderRequest request) {
        return ResponseEntity.ok(paymentAttemptService.recordVerification(
                request.getOrderId(), request.getPaymentId(),
                () -> storeKioskService.verifyAndIssue(slug, request)));
    }

    @Operation(summary = "Email me a sign-in code for my kiosk room",
            description = "Public. Sends a one-time code to the address used at the till, if that address "
                    + "bought something and its account is still unclaimed. The response is identical in "
                    + "every case — found, not found, or inside the resend cooldown — so this cannot be "
                    + "used to ask whether somebody has shopped here. Per-IP rate-limited.")
    @SecurityRequirements
    @PostMapping("/re-entry")
    public ResponseEntity<KioskReentryStatusResponse> requestReentry(
            @Valid @RequestBody KioskReentryRequest request) {
        return ResponseEntity.accepted().body(kioskReentryService.requestCode(request.getEmail()));
    }

    @Operation(summary = "Sign in with an emailed kiosk code",
            description = "Public. Exchanges a correct one-time code for a session on the account the "
                    + "purchase lives on. Single-use, expiring, and throttled by attempt count.")
    @SecurityRequirements
    @PostMapping("/re-entry/confirm")
    public ResponseEntity<AuthResponse> confirmReentry(
            @Valid @RequestBody KioskReentryConfirmRequest request) {
        return ResponseEntity.ok(kioskReentryService.confirm(request.getEmail(), request.getCode()));
    }
}
