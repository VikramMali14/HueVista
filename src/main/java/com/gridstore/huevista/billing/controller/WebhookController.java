package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.service.RazorpayWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/billing/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Payment gateway event receivers")
public class WebhookController {

    private final RazorpayWebhookService webhookService;

    @Operation(summary = "Razorpay webhook receiver",
            description = "Receives and processes subscription lifecycle events from Razorpay. Verified via HMAC-SHA256 signature.")
    @PostMapping("/razorpay")
    public ResponseEntity<Map<String, String>> razorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        try {
            webhookService.handleWebhook(payload, signature, eventId);
            return ResponseEntity.ok(Map.of("status", "processed"));
        } catch (SecurityException e) {
            log.warn("Webhook signature invalid: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        } catch (Exception e) {
            // Return 5xx so Razorpay RETRIES (its webhooks use exponential backoff).
            // Returning 200 here would silently drop a genuine event — e.g. a transient DB
            // failure during activation would permanently lose that subscription transition.
            // Don't echo the raw exception message back to the caller.
            log.error("Webhook processing error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Processing failed"));
        }
    }
}
