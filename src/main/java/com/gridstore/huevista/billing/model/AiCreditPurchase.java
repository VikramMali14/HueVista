package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records each Razorpay payment already redeemed for AI credits.
 *
 * The unique {@code paymentId} is what makes crediting idempotent: one verified Checkout
 * payment buys exactly one batch of credits. Without it a client could re-POST the same
 * valid (order_id, payment_id, signature) triple and mint credits out of a single payment
 * — the signature stays valid on every replay, so the constraint, not the check, is what
 * stops it.
 *
 * <p>The price is stored broken out — list price, discount, what was actually charged —
 * rather than as the total alone. The launch discount is a configured number that will
 * change, and a receipt that only says "₹99" cannot afterwards say whether that was one
 * credit at the launch rate or half a credit at some later one.
 */
@Entity
@Table(name = "ai_credit_purchases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCreditPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String paymentId;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    /** Credits this payment bought. */
    @Column(nullable = false)
    private int credits;

    /** The undiscounted price of ONE credit at the time of purchase, in paise. */
    @Column(nullable = false)
    private int listPricePaise;

    /** The launch discount applied, as a whole percentage. 0 once the launch is over. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int discountPercent = 0;

    /** What was actually charged, in paise, for the whole order. */
    @Column(nullable = false)
    private int amountPaise;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public static AiCreditPurchase of(String paymentId, String orderId, String userId,
                                      int credits, int listPricePaise, int discountPercent,
                                      int amountPaise) {
        return AiCreditPurchase.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .credits(credits)
                .listPricePaise(listPricePaise)
                .discountPercent(discountPercent)
                .amountPaise(amountPaise)
                .build();
    }
}
