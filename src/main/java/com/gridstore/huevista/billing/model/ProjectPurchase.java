package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records each Razorpay payment already redeemed for one extra project.
 *
 * The unique {@code paymentId} makes crediting idempotent: a verified Checkout payment
 * buys exactly one project. Without it a client could re-POST the same valid
 * (order_id, payment_id, signature) triple and mint a project on every replay — the
 * signature is a plain HMAC with no nonce and no expiry, so it stays valid forever.
 *
 * {@link #plan} records the tier the price was read off, because that price is not a
 * constant: the same project costs ₹99 with no plan and ₹45 on Business, and a receipt
 * that doesn't say which rate applied can't be checked afterwards.
 */
@Entity
@Table(name = "project_purchases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String paymentId;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    /** The tier whose rate this was charged at. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Plan plan;

    /** What was actually charged, in paise. */
    @Column(nullable = false)
    private int amountPaise;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public static ProjectPurchase of(String paymentId, String orderId, String userId,
                                     Plan plan, int amountPaise) {
        return ProjectPurchase.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .plan(plan)
                .amountPaise(amountPaise)
                .build();
    }
}
