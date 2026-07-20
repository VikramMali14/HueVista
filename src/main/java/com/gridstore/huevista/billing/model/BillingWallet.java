package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A retailer's prepaid billing wallet: money added by Razorpay top-up, spent on
 * pay-per-use overage (extra images at Rs. 50 + GST, extra AI auto-masks at
 * Rs. 25 + GST) once the plan's monthly allowances are used up.
 *
 * Deliberately separate from the kiosk-earnings wallet in the {@code store}
 * package: that one holds money the shop EARNED and can redeem as a payout,
 * while this one holds money the shop PRE-PAID for usage — top-ups are not
 * redeemable, so mixing the two would turn top-ups into a payout channel.
 *
 * Debits go through {@code BillingWalletRepository#debitIfSufficient}, a single
 * conditional UPDATE, so two concurrent purchases can never overdraw the
 * balance. Every movement is journaled in {@link BillingWalletTransaction}.
 */
@Entity
@Table(name = "billing_wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String userId;

    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    @Builder.Default
    private long balancePaise = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
