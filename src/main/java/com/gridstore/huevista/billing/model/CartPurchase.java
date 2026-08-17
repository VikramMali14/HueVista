package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One basket a customer paid for: what was in it, what it was priced at, and what came off.
 *
 * <p><b>Its first job is replay protection.</b> A verified Razorpay signature stays valid
 * for ever, so without a claimed-payments row a client could re-POST the same triple and
 * mint projects and credits out of one payment. The unique {@link #paymentId} is what makes
 * that impossible; the pre-check in the service only keeps the common case readable.
 *
 * <p><b>Its second is the receipt.</b> The lines are stored as QUANTITIES beside the unit
 * prices they were charged at, not as one total. Catalogue prices and offers are
 * configuration and will move, so "₹537" a year from now cannot afterwards say whether that
 * was three projects at ₹149 less 10%, or something else at a later price. Every number the
 * cart put in front of the buyer is kept here, because that is what a refund or a dispute
 * is argued from.
 *
 * <p>Deliberately a separate table from {@link ProjectPurchase} and
 * {@link AiCreditPurchase} rather than two rows in those. One payment bought both things at
 * once, and splitting it across two ledgers would make the discount — which applies to the
 * basket, not to any line in it — impossible to attribute to either.
 */
@Entity
@Table(name = "cart_purchases",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_purchase_payment", columnNames = "paymentId"),
        indexes = @Index(name = "idx_cart_purchases_user", columnList = "userId, createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The Razorpay payment. Unique — this is the replay guard. */
    @Column(nullable = false, unique = true, length = 255)
    private String paymentId;

    @Column(nullable = false, length = 255)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    // ── The lines, as they were rung up ────────────────────────────────────

    /** Projects bought on their own, and what one was charged at. */
    @Column(nullable = false)
    private int projectQty;

    @Column(nullable = false)
    private int projectPricePaise;

    /** AI image credits bought on their own, and what one was charged at. */
    @Column(nullable = false)
    private int creditQty;

    @Column(nullable = false)
    private int creditPricePaise;

    /** Combos bought, and what one was charged at. */
    @Column(nullable = false)
    private int comboQty;

    @Column(nullable = false)
    private int comboPricePaise;

    // ── What it came to, and what it granted ───────────────────────────────

    /** The basket before any offer. */
    @Column(nullable = false)
    private int subtotalPaise;

    /** The offer that was applied, and what it took off. Blank and 0 when none was. */
    @Column(length = 32)
    private String discountCode;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int discountPercent = 0;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int discountPaise = 0;

    /** What was actually charged. */
    @Column(nullable = false)
    private int amountPaise;

    /** Projects and credits this basket granted, combos included. Stored rather than
     *  recomputed from the quantities, because the combo's composition is configuration
     *  too and a change to it must not rewrite what an old order handed over. */
    @Column(nullable = false)
    private int projectsGranted;

    @Column(nullable = false)
    private int creditsGranted;

    /** How long both were sold as being good for. */
    @Column(nullable = false)
    private int validDays;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
