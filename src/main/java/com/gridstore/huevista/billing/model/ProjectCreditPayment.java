package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records each Razorpay payment already redeemed for a one-time project credit.
 *
 * The unique {@code paymentId} makes crediting idempotent: a verified Checkout
 * payment buys exactly ONE project credit. Without this, a client could re-POST
 * the same valid (order_id, payment_id, signature) triple to
 * {@code /api/billing/project-credit/verify} repeatedly and mint unlimited credits
 * from a single payment — the signature stays valid on every replay.
 */
@Entity
@Table(name = "project_credit_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectCreditPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String paymentId;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public static ProjectCreditPayment of(String paymentId, String orderId, String userId) {
        return ProjectCreditPayment.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .build();
    }
}
