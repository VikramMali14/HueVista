package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One movement on a {@link BillingWallet}: positive amounts are money in
 * (top-ups), negative amounts are money out (pay-per-use purchases). The
 * journal is append-only — the balance column is the source of truth for
 * spending power, the journal is the statement the retailer sees.
 *
 * This is RUPEES only. Kiosk reward points are a separate ledger
 * ({@link com.gridstore.huevista.billing.model.RewardPointsLot}) because they buy at
 * their own prices and expire after a year, neither of which prepaid money does.
 */
@Entity
@Table(name = "billing_wallet_transactions",
        indexes = @Index(name = "idx_billing_wallet_txn_user", columnList = "userId, createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingWalletTransaction {

    public enum Type {
        /** Razorpay top-up credited to the wallet. */
        TOPUP,
        /** One extra image bought once the monthly image quota was spent. */
        EXTRA_IMAGE,
        /** One extra AI auto-mask run bought once the monthly allowance was spent. */
        EXTRA_AUTO_MASK,
        /** One project bought from the balance instead of through Checkout. */
        PROJECT_CREDIT,
        /** Another validity window on an expired project, bought from the balance. */
        PROJECT_REOPEN,
        /**
         * Balance written off back to the retailer by an admin (the money movement itself
         * is manual). Without this the prepaid balance of a retailer who cancelled or
         * closed their account was stranded: top-ups and spending both require an active
         * plan, so there was no path back out.
         */
        REFUND
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    /** Signed paise: positive = credit (top-up), negative = debit (purchase). */
    @Column(nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    /** External reference — the Razorpay payment id for top-ups; null for debits. */
    private String reference;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
