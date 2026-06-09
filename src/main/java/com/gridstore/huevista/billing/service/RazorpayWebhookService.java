package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.model.ProcessedWebhookEvent;
import com.gridstore.huevista.billing.repository.ProcessedWebhookEventRepository;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayWebhookService {

    private final BillingService billingService;
    private final ProcessedWebhookEventRepository processedEventRepository;

    @Value("${razorpay.webhook-secret:}")
    private String webhookSecret;

    @Transactional
    public void handleWebhook(String payload, String signature, String eventId) {
        verifySignature(payload, signature);

        // Idempotency: Razorpay retries deliveries, and a captured request could be
        // replayed within signature validity. Each event id is processed at most once;
        // the marker insert shares this transaction, so a mid-processing failure rolls
        // it back and the retry is processed cleanly. A duplicate racing insert dies
        // on the primary key, returns 5xx, and the retry is skipped here.
        if (StringUtils.hasText(eventId)) {
            if (processedEventRepository.existsById(eventId)) {
                log.info("Razorpay webhook already processed, skipping: {}", eventId);
                return;
            }
            processedEventRepository.save(ProcessedWebhookEvent.builder().eventId(eventId).build());
        } else {
            log.warn("Razorpay webhook without X-Razorpay-Event-Id — processing without replay protection");
        }

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
