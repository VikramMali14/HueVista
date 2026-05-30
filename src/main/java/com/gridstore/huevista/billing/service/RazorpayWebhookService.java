package com.gridstore.huevista.billing.service;

import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayWebhookService {

    private final BillingService billingService;

    @Value("${razorpay.webhook-secret:}")
    private String webhookSecret;

    public void handleWebhook(String payload, String signature) {
        verifySignature(payload, signature);

        JSONObject event = new JSONObject(payload);
        String eventType = event.getString("event");
        JSONObject entity = event.getJSONObject("payload");

        log.info("Razorpay webhook received: {}", eventType);

        switch (eventType) {
            case "subscription.activated" -> {
                JSONObject sub = entity.getJSONObject("subscription").getJSONObject("entity");
                billingService.activateSubscription(
                        sub.getString("id"),
                        sub.optLong("charge_at", 0),
                        sub.optLong("current_end", 0));
            }
            case "subscription.cancelled" -> {
                String subId = entity.getJSONObject("subscription").getJSONObject("entity").getString("id");
                billingService.markCancelled(subId);
            }
            case "subscription.completed" -> {
                String subId = entity.getJSONObject("subscription").getJSONObject("entity").getString("id");
                billingService.markCompleted(subId);
            }
            case "subscription.halted" -> {
                String subId = entity.getJSONObject("subscription").getJSONObject("entity").getString("id");
                billingService.markHalted(subId);
            }
            case "payment.captured" -> {
                JSONObject payment = entity.getJSONObject("payment").getJSONObject("entity");
                String subId = payment.optString("subscription_id", "");
                if (!subId.isBlank()) {
                    // Approximate next period end as 30 days from now; Razorpay will send subscription.charged too
                    long approxNextEnd = System.currentTimeMillis() / 1000 + 30L * 24 * 3600;
                    billingService.handlePaymentCaptured(subId, approxNextEnd);
                }
            }
            default -> log.debug("Unhandled Razorpay event: {}", eventType);
        }
    }

    private void verifySignature(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            // Fail CLOSED. With no secret we cannot trust the payload, and this endpoint is
            // public (permitAll). Accepting unsigned events would let anyone forge
            // subscription.activated / payment.captured to grant plans or reset AI quota.
            log.error("Razorpay webhook secret not configured — rejecting webhook. Set RAZORPAY_WEBHOOK_SECRET.");
            throw new SecurityException("Webhook signature verification is not configured.");
        }
        try {
            boolean valid = Utils.verifyWebhookSignature(payload, signature, webhookSecret);
            if (!valid) {
                throw new SecurityException("Invalid Razorpay webhook signature");
            }
        } catch (com.razorpay.RazorpayException e) {
            throw new SecurityException("Webhook signature verification failed: " + e.getMessage());
        }
    }
}
