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
 * constant: the same project costs ₹199 with no plan and ₹45 on Business, and a receipt
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

    /**
     * What the money bought. Every row here used to be one extra project; the table now
     * also carries reopens, three-project bundles and AI render top-ups, and the amount
     * alone no longer says which — a ₹99 row is a closed reopen or a render depending
     * only on this column.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private Purpose purpose = Purpose.PROJECT;

    /** Projects this purchase granted. Three for a bundle, one otherwise, zero for a
     *  reopen and a render — neither of which adds to the allowance. */
    @Column(nullable = false)
    @Builder.Default
    private int credits = 1;

    /** The project a reopen or a render top-up was bought for. Null for a plain purchase,
     *  which buys an allowance rather than anything attached to one room. */
    @Column(length = 255)
    private String projectId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Purpose {
        /** One extra project at the buyer's tier rate. */
        PROJECT,
        /** Three projects for two projects' money. */
        BUNDLE,
        /** Another validity window, or a closed project opened back up. */
        REOPEN,
        /** One more AI render on a project that already spent its included one. */
        RENDER
    }

    public static ProjectPurchase of(String paymentId, String orderId, String userId,
                                     Plan plan, int amountPaise) {
        return of(paymentId, orderId, userId, plan, amountPaise, Purpose.PROJECT, 1, null);
    }

    public static ProjectPurchase of(String paymentId, String orderId, String userId,
                                     Plan plan, int amountPaise, Purpose purpose,
                                     int credits, String projectId) {
        return ProjectPurchase.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .plan(plan)
                .amountPaise(amountPaise)
                .purpose(purpose)
                .credits(credits)
                .projectId(projectId)
                .build();
    }
}
