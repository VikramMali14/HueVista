package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One paid-for project that has not been created yet — the ledger behind "buy a
 * project".
 *
 * A row per purchase rather than a counter on the account, for two reasons. The price
 * is not fixed (a project costs less while the buyer is subscribed), so the amount
 * actually paid has to survive on the thing it bought; and consumption becomes a
 * compare-and-set on {@link #consumedAt} instead of a read-modify-write on a total, so
 * two parallel "create project" requests can never spend the same credit twice.
 *
 * {@link #validDays} is captured at purchase time so a later change to the standard
 * window never retroactively shortens (or lengthens) a credit somebody already bought.
 */
@Entity
@Table(name = "project_credits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectCredit {

    public enum Source {
        /** Bought by the account holder through Razorpay Checkout. */
        PURCHASE,
        /**
         * Bought from the prepaid rupee wallet. The money was already collected when it
         * entered the wallet, so there is no payment id on this one.
         */
        WALLET,
        /** Bought with kiosk reward points — no money moved for this one at all. */
        POINTS,
        /** Issued by an administrator without a payment (support, goodwill, testing). */
        GRANT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int pointsSpent = 0;

    /** Days of access this credit opens on the project it becomes. */
    @Column(nullable = false, columnDefinition = "integer not null default 30")
    @Builder.Default
    private int validDays = 30;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private Source source = Source.PURCHASE;

    /** The project this credit became. Set at the moment it is spent. */
    private String projectId;

    /** Set when spent. Null means the credit is still available. */
    private LocalDateTime consumedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
