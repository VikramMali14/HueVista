package com.gridstore.huevista.billing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Idempotency marker for inbound payment-gateway webhooks. Razorpay retries
 * deliveries (timeouts, our 5xx responses), and a captured request could be
 * replayed; recording each provider event id ensures an event mutates billing
 * state at most once.
 */
@Entity
@Table(name = "processed_webhook_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedWebhookEvent {

    /** The provider's unique event id (Razorpay: X-Razorpay-Event-Id header). */
    @Id
    @Column(length = 64)
    private String eventId;

    @CreationTimestamp
    private LocalDateTime processedAt;
}
