package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One account's AI image credits: the balance that buys photorealistic renders.
 *
 * This is a second wallet beside {@link RewardPointsLot}, and the separation is
 * deliberate rather than an oversight. Points are a shop-side loyalty currency — earned at
 * a kiosk, dated, expiring, spendable only by a RETAILER on shop things (extra projects,
 * reopens). AI credits are the opposite on every one of those axes: bought with money,
 * never earned, never expiring, and held by the person who wants the picture — which is
 * usually the CUSTOMER a shop handed a project to, an account that can hold no points at
 * all. Folding them into the point ledger would have meant either letting customers hold
 * points (and with them the shop prices) or refusing customers the thing they came to buy.
 *
 * <p><b>One row per account, and the balance lives on it.</b> Not summed from the journal
 * on every read: a render spends a credit on the request thread and two browser tabs must
 * not each pass a "you have one left" check against the same credit. The spend is a
 * conditional UPDATE ({@code balance >= :credits}) so the database decides which tab wins.
 * {@link AiCreditTransaction} is the statement beside it, not the source of truth.
 *
 * <p>Credits never expire and never convert back to money. They are a prepayment for a
 * specific piece of work at a fixed price, so an expiry would be a charge for nothing, and
 * a payout would make this a stored-value instrument rather than a prepaid service.
 */
@Entity
@Table(name = "ai_credit_wallets",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_credit_wallet_user", columnNames = "userId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCreditWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The account that holds these credits. One wallet per account, ever. */
    @Column(nullable = false, unique = true)
    private String userId;

    /**
     * Spendable credits right now.
     *
     * Never negative: every debit goes through the conditional UPDATE in
     * {@code AiCreditWalletRepository#spendIfAvailable}, which simply does not fire when
     * the balance is short. There is no debt lot here of the kind the point ledger carries,
     * because nothing credits this wallet that can later be clawed back — a credit arrives
     * only after a Razorpay payment has been verified and claimed.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int balance = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
