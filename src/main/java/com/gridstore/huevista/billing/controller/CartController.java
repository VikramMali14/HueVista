package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.CartCatalogueResponse;
import com.gridstore.huevista.billing.dto.CartOrderResponse;
import com.gridstore.huevista.billing.dto.CreateCartOrderRequest;
import com.gridstore.huevista.billing.dto.VerifyCartPurchaseRequest;
import com.gridstore.huevista.billing.service.CartPurchaseService;
import com.gridstore.huevista.billing.service.PaymentAttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * The customer's counter: projects, AI image credits and the combo of the two, bought
 * together in one payment.
 *
 * <p>Three endpoints and no fourth. There is deliberately no "price my basket" call — the
 * client multiplies the catalogue's own rates by its quantities to draw the running total,
 * and {@code /order} prices it again server-side when real money is about to move. A quote
 * endpoint would add a round trip per tap on a plus button and would still not be the
 * authority, so it would buy nothing but a second place for the arithmetic to differ.
 *
 * <p>CUSTOMER accounts only, enforced in the service so it holds for every caller rather
 * than for this controller alone. A shop's prices move with its plan and its projects land
 * on that plan's allowance, so it buys from {@code /api/billing/projects} instead.
 */
@RestController
@RequestMapping("/api/billing/cart")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "Projects and AI image credits, bought by the basket")
public class CartController {

    private final CartPurchaseService cartService;
    private final PaymentAttemptService paymentAttemptService;

    @Operation(summary = "The counter",
            description = "What is for sale and what it costs — a project, an AI image "
                    + "credit, and the combo — with the offers on the board, how long a "
                    + "purchase lasts, and what this account already holds. Returns "
                    + "eligible=false for an account that does not buy from this list.")
    @GetMapping
    public ResponseEntity<CartCatalogueResponse> catalogue(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(cartService.catalogue(userDetails.getUsername()));
    }

    @Operation(summary = "Buy a basket (order)",
            description = "Creates a Razorpay order for the quantities given. The amount is "
                    + "derived server-side from the catalogue's own rates and the offer the "
                    + "subtotal has earned, so the client never names a price — and a code "
                    + "that has not been earned takes nothing off.")
    @PostMapping("/order")
    public ResponseEntity<CartOrderResponse> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateCartOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cartService.createOrder(userDetails.getUsername(), request));
    }

    @Operation(summary = "Buy a basket (verify)",
            description = "Verifies the Razorpay Checkout signature and hands over the "
                    + "projects and credits the order was for, each good for a year. "
                    + "Replay-protected — one payment is redeemed exactly once. Returns the "
                    + "refreshed counter with the new balances on it.")
    @PostMapping("/verify")
    public ResponseEntity<CartCatalogueResponse> verifyPurchase(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyCartPurchaseRequest request) {
        // Recorded through the audit service like every other verification, so a basket that
        // was paid for and then failed to redeem is visible beside the ones that worked.
        return ResponseEntity.ok(paymentAttemptService.recordVerification(
                request.getOrderId(), request.getPaymentId(),
                () -> cartService.verifyAndCredit(userDetails.getUsername(), request)));
    }
}
