package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records each Razorpay payment already redeemed for points.
 *
 * The unique {@code paymentId} makes crediting idempotent: a verified Checkout payment
 * buys exactly one batch of points. Without it a client could re-POST the same valid
 * (order_id, payment_id, signature) triple and mint points from a single payment — the
 * signature stays valid on every replay.
 *
 * Besides a subscription, this is the only place money now enters the product. It used
 * to guard per-item project and reopen purchases too; those are bought with points now,
 * so what it guards is the top-up itself.
 */
@Entity
@Table(name = "points_purchases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointsPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String paymentId;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    /** Points credited by this payment. */
    @Column(nullable = false)
    private int points;

    /** What was actually charged, in paise — points x the configured rate. */
    @Column(nullable = false)
    private int amountPaise;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public static PointsPurchase of(String paymentId, String orderId, String userId,
                                    int points, int amountPaise) {
        return PointsPurchase.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .points(points)
                .amountPaise(amountPaise)
                .build();
    }
}
