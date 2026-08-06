package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.PaymentAttemptEventRequest;
import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.service.PaymentAttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * The browser's side of the payment audit trail.
 *
 * <p>Three of the six outcomes an attempt can have only exist in the buyer's browser: the
 * Checkout window opening, the buyer closing it, and Razorpay refusing the card. None of
 * those reach our server on their own — no order is placed, no webhook fires — so without
 * this endpoint the report could only ever show payments that worked.
 *
 * <p>Returns 204 for everything, including references we do not recognise. Reporting is
 * best-effort telemetry; a checkout must never show the buyer an error because a
 * bookkeeping call failed, and a 404 here would also confirm to a prober which references
 * exist.
 */
@RestController
@RequestMapping("/api/billing/attempts")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Subscription management and usage tracking")
public class PaymentAttemptController {

    private final PaymentAttemptService paymentAttemptService;

    @Operation(summary = "Report what happened to a checkout",
            description = "Records that the buyer saw, closed, or was refused at a Razorpay "
                    + "Checkout, along with the page they were on. Always 204 — this is "
                    + "telemetry and must never fail a payment flow. PAID cannot be reported "
                    + "here; only a verified signature sets that.")
    @PostMapping("/{reference}/events")
    public ResponseEntity<Void> reportEvent(
            @PathVariable String reference,
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentAttemptEventRequest request) {

        PaymentAttemptStatus status = parseStatus(request.getStatus());
        if (status == null || !PaymentAttemptService.isClientReportable(status)) {
            return ResponseEntity.noContent().build();
        }

        PaymentAttempt attempt = paymentAttemptService.find(reference);
        if (attempt == null) {
            return ResponseEntity.noContent().build();
        }

        // An attempt that belongs to an account may only be spoken for by that account.
        // Kiosk attempts have no owner — the buyer is a walk-in with no session — so for
        // those the unguessable Razorpay order id is the only capability there is, which
        // is the same thing that protects the kiosk's own verify endpoint.
        String owner = attempt.getUserId();
        if (owner != null) {
            String caller = userDetails == null ? null : userDetails.getUsername();
            if (!owner.equals(caller)) {
                return ResponseEntity.noContent().build();
            }
        }

        paymentAttemptService.recordClientEvent(
                reference, status,
                request.getPageUrl(), request.getReferrer(), request.getPaymentId(),
                request.getErrorCode(), request.getErrorDescription(),
                request.getErrorSource(), request.getErrorStep(), request.getErrorReason());

        return ResponseEntity.noContent().build();
    }

    private static PaymentAttemptStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PaymentAttemptStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
