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

    /**
     * What the payment bought. Buying a new project and reopening a lapsed one are
     * one-time payments on the same rail at different prices, and only this (plus
     * {@link #projectId} for a reopen) tells them apart afterwards.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private Purpose purpose = Purpose.PROJECT_CREDIT;

    /** What was actually charged, in paise — the price varies with subscription state. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int amountPaise = 0;

    /** The project a REOPEN payment extended. Null for a project-credit purchase. */
    private String projectId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Purpose {
        /** Buys one project the account may create. */
        PROJECT_CREDIT,
        /** Adds another validity window to an existing project that had lapsed. */
        PROJECT_REOPEN
    }

    public static ProjectCreditPayment of(String paymentId, String orderId, String userId) {
        return ProjectCreditPayment.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .build();
    }

    public static ProjectCreditPayment of(String paymentId, String orderId, String userId,
                                          Purpose purpose, int amountPaise, String projectId) {
        return ProjectCreditPayment.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .purpose(purpose)
                .amountPaise(amountPaise)
                .projectId(projectId)
                .build();
    }
}
