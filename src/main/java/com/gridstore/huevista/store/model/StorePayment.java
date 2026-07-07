package com.gridstore.huevista.store.model;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A verified kiosk payment. The unique {@code paymentId} column is the replay
 * backstop — one Razorpay payment buys exactly one access code — and the split
 * columns are the wallet's source of truth: the retailer's balance is
 * SUM(retailerSharePaise) minus their non-rejected redemptions.
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

    /** The platform's base share (Rs.50 by default), in paise. */
    @Column(nullable = false)
    private int platformFeePaise;

    /** What accrues to the retailer's wallet: amount minus the platform base. */
    @Column(nullable = false)
    private int retailerSharePaise;

    /** The access code this payment bought (set right after the payment row is safe). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_code_id")
    private CustomerAccessCode accessCode;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
