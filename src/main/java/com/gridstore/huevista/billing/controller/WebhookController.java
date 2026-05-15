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
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        try {
            webhookService.handleWebhook(payload, signature);
            return ResponseEntity.ok(Map.of("status", "processed"));
        } catch (SecurityException e) {
            log.warn("Webhook signature invalid: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        } catch (Exception e) {
            log.error("Webhook processing error: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
