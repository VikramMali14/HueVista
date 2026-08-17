package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One batch of AI image credits, with the date it was sold to lapse on.
 *
 * <p><b>Why the wallet was not enough.</b> {@link AiCreditWallet} holds a single balance,
 * which is exactly right for spending — the debit is one conditional UPDATE, so two browser
 * tabs cannot both pass a "you have one left" check against the same credit. What a bare
 * balance cannot say is WHEN any of it dies. The customer catalogue sells credits with a
 * year on them, and a year has to be counted from each purchase rather than from the last
 * thing the account did, so a customer who buys in January and again in June holds two
 * batches ageing independently. Only a row per batch can answer "how many of mine lapse in
 * March", which is what the wallet panel shows and what the sweep works from.
 *
 * <p><b>The wallet is still the truth about what is spendable.</b> These lots run beside it,
 * not instead of it: a spend takes the balance down through the wallet's compare-and-set and
 * then draws the same number out of the lots, soonest expiry first, so nobody loses credits
 * that were about to lapse while later ones sat untouched. The invariant is that the lots'
 * remaining credits sum to the wallet balance, and the one place it could drift — a wallet
 * that predates this table — is closed by the migration, which opens a never-expiring lot
 * for every balance already on the books.
 *
 * <p><b>A null {@link #expiresAt} means never.</b> That is not a missing value: AI credits
 * were sold for a year and a half under a promise that they never expire, and a shop still
 * buys them on those terms. Only what is sold WITH a date gets one, and the date is stamped
 * at purchase — so a rule that changes tomorrow cannot reach back and age a credit somebody
 * already paid for.
 */
@Entity
@Table(name = "ai_credit_lots",
        indexes = {
            @Index(name = "idx_ai_credit_lot_user", columnList = "userId, expiresAt"),
            @Index(name = "idx_ai_credit_lot_expiry", columnList = "expiresAt")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCreditLot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    /** Credits this batch opened with. Kept for the statement even once spent down. */
    @Column(nullable = false)
    private int credits;

    /** Credits still unspent in this batch. Never negative — nothing claws a credit back
     *  after the fact, because one only ever arrives on a verified payment. */
    @Column(nullable = false)
    private int creditsRemaining;

    /** When this batch lapses. Null means never, which is the honest reading of every
     *  credit sold before the catalogue existed and of every shop purchase since. */
    private LocalDateTime expiresAt;

    /** The Razorpay payment that bought them, or the administrator who gave them. */
    @Column(length = 255)
    private String sourceReference;

    /** Set by the sweep once whatever was left in here was written off. */
    private LocalDateTime expiredAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** Credits that will still be here to spend, as far as this batch is concerned. */
    public boolean isSpendable() {
        return expiredAt == null && creditsRemaining > 0;
    }
}
