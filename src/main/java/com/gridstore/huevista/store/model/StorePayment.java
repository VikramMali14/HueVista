package com.gridstore.huevista.store.model;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A verified kiosk payment. The unique {@code paymentId} column is the replay
 * backstop — one Razorpay payment buys exactly one access code.
 *
 * The whole {@code amountPaise} is HueVista's revenue; the shop takes no share of it.
 * {@code bonusPointsPaise} records the reward points the sale earned the shop, which
 * live in the owner's billing wallet — this row is the audit trail for why those points
 * exist, not a balance anyone draws from.
 */
@Entity
@Table(name = "store_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_link_id", nullable = false)
    private StoreLink storeLink;

    // Denormalized from the link so wallet queries never join through it.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Razorpay payment id — unique so a replayed verification can't double-issue. */
    @Column(name = "payment_id", unique = true, nullable = false)
    private String paymentId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    /** What the customer actually paid, in paise. */
    @Column(nullable = false)
    private int amountPaise;

    /** What HueVista keeps in cash: the amount paid, less the value of the points awarded. */
    @Column(nullable = false)
    private int platformFeePaise;

    /**
     * Reward points this sale earned the shop, in paise of spending power. Credited to
     * the owner's billing wallet at verify time; recorded here so a refund knows how many
     * to take back and so the shop's kiosk statement can show what each sale earned.
     */
    @Column(name = "bonus_points_paise", nullable = false)
    private int bonusPointsPaise;

    /** The access code this payment bought (set right after the payment row is safe). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_code_id")
    private CustomerAccessCode accessCode;

    /**
     * Set when Razorpay tells us the money went back to the customer (refund or
     * chargeback). A reversed payment is excluded from the wallet balance: without this
     * the retailer's share of a refunded sale stayed spendable, so a customer could pay,
     * charge back, and the shop could still cash the share out over UPI — a real, silent
     * cash loss for the platform. Kept as a timestamp (not a delete) so the ledger and
     * the issued access code stay auditable.
     */
    private LocalDateTime reversedAt;

    /** Paise actually returned to the customer — informational; any refund reverses the row. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int refundedPaise = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public boolean isReversed() {
        return reversedAt != null;
    }
}
