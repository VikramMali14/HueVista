package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records each Razorpay payment already redeemed for a subscription activation.
 *
 * The Razorpay subscription signature is {@code HMAC(payment_id|subscription_id, secret)}
 * — no nonce, no timestamp — so a payload captured once at Checkout stays valid forever.
 * Without this claim a shop could store their first successful payload and re-POST it to
 * {@code /api/billing/subscriptions/verify} every time the plan lapsed, renewing free of
 * charge indefinitely.
 *
 * Same job the {@link PointsPurchase} ledger does for points top-ups: one payment id,
 * claimed exactly once, enforced by the primary key so two concurrent submits can't both
 * win.
 */
@Entity
@Table(name = "subscription_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPayment {

    /** The Razorpay payment id — the thing being claimed, so it IS the key. */
    @Id
    private String paymentId;

    /** The gateway subscription the payment authorized. */
    @Column(nullable = false)
    private String razorpaySubscriptionId;

    /** Our subscription row it activated. */
    @Column(nullable = false)
    private String subscriptionId;

    @Column(nullable = false)
    private String userId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public static SubscriptionPayment of(String paymentId, String razorpaySubscriptionId,
                                         String subscriptionId, String userId) {
        return SubscriptionPayment.builder()
                .paymentId(paymentId)
                .razorpaySubscriptionId(razorpaySubscriptionId)
                .subscriptionId(subscriptionId)
                .userId(userId)
                .build();
    }
}
