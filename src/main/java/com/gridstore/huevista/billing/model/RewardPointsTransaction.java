package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One movement on a shop's reward-point balance: positive is earned, negative is spent,
 * clawed back or expired. Append-only — the lots hold spending power, this is the
 * statement the shop reads.
 */
@Entity
@Table(name = "reward_points_transactions",
        indexes = @Index(name = "idx_reward_points_txn_user", columnList = "userId, createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardPointsTransaction {

    public enum Type {
        /** A walk-in bought a visualisation through this shop's kiosk link. */
        KIOSK_EARNED,
        /** The shop bought points outright. Worth exactly the same as earned ones. */
        PURCHASED,
        /** That kiosk payment was refunded, so its points came back out. */
        KIOSK_REVERSED,
        /** Points that reached their first birthday unspent. */
        EXPIRED,
        SPENT_ON_IMAGE,
        SPENT_ON_AUTO_MASK,
        SPENT_ON_PROJECT,
        SPENT_ON_PROJECT_REOPEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    /** Signed points: positive = earned, negative = spent / reversed / expired. */
    @Column(nullable = false)
    private int points;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    /** Kiosk payment id for earn/reverse; the project id for a project spend; else null. */
    private String reference;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
