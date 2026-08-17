package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One movement on an account's AI credit balance: positive is bought or given back,
 * negative is spent. Append-only — {@link AiCreditWallet} holds the spending power, this
 * is the statement the holder reads.
 *
 * <p>Every row carries the balance it left behind. That is redundant with replaying the
 * journal and worth the column anyway: a holder looking at "1 credit spent" wants to know
 * what they had afterwards, and a support request about a missing credit is answered by
 * reading one row rather than by adding up every row that came before it.
 */
@Entity
@Table(name = "ai_credit_transactions",
        indexes = @Index(name = "idx_ai_credit_txn_user", columnList = "userId, createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCreditTransaction {

    public enum Type {
        /** Bought with money at the launch price. The only way credits are created. */
        PURCHASED,
        /** Spent on one AI image. */
        SPENT_ON_RENDER,
        /**
         * Handed back because the render it paid for failed.
         *
         * A separate type from PURCHASED even though both are positive: a refund must
         * never read as revenue on a statement, and "you were charged and then not" is
         * exactly the line a customer chasing a missing credit is looking for.
         */
        RENDER_REFUNDED,
        /** Given by an administrator — support, goodwill, a launch promotion. */
        GRANTED,
        /**
         * Written off because the year it was sold with ran out.
         *
         * <p>Only ever reaches credits bought with a validity on them — the customer
         * catalogue's, which says "valid for a year" on the line before the money moves.
         * Credits sold under the old promise carry no date at all and are never touched by
         * the sweep, because expiring those would be changing a deal after it was struck.
         */
        EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    /** Signed credits: positive = bought or refunded, negative = spent. */
    @Column(nullable = false)
    private int credits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    /** The wallet balance immediately after this movement. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int balanceAfter = 0;

    /** The Razorpay payment id on a purchase, the project id on a render spend or
     *  refund, the granting administrator on a grant. Null when there is nothing to name. */
    @Column(length = 255)
    private String reference;

    /** What a human should read on the statement line. Kept beside the type because a
     *  type is a constant and this can say "1 AI image · Living room" without one. */
    @Column(length = 255)
    private String note;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
