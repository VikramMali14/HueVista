package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One batch of reward points earned at a moment in time, with its own expiry.
 *
 * Points are held in dated lots rather than as a single counter because they expire a
 * year after they are EARNED, not a year after the account last did anything. A shop
 * that earns steadily holds several lots ageing independently, and only a lot-level
 * record can say which points die on which day — which is also what the 10-day warning
 * and last-day notices are addressed from.
 *
 * <p>Spending consumes the soonest-expiring lot first, so a shop never loses points it
 * could have used while newer ones sat untouched.
 *
 * <p>A lot with {@code expiresAt == null} and a NEGATIVE {@code pointsRemaining} is a
 * debt: points clawed back after a kiosk refund that the shop had already spent. Debt
 * does not expire (that would forgive it silently) and is settled out of the next points
 * earned, before any new lot is opened.
 */
@Entity
@Table(name = "reward_points_lots",
        indexes = {
            @Index(name = "idx_reward_points_lot_user", columnList = "userId, expiresAt"),
            @Index(name = "idx_reward_points_lot_expiry", columnList = "expiresAt")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardPointsLot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    /** Points this lot opened with. Kept for the statement even once spent down. */
    @Column(nullable = false)
    private int pointsEarned;

    /** Unspent points still in this lot. Negative only on a debt lot. */
    @Column(nullable = false)
    private int pointsRemaining;

    /** When these points expire. Null on a debt lot — a shortfall must not age away. */
    private LocalDateTime expiresAt;

    /** The kiosk payment that earned them, so a refund can find its own lot. */
    private String sourceReference;

    /** Set when the 10-days-left warning went out, so the daily job sends it once. */
    private LocalDateTime expiryWarningSentAt;

    /** Set when the last-day notice went out. */
    private LocalDateTime expiryNoticeSentAt;

    /** Set by the expiry sweep once the remaining points were written off. */
    private LocalDateTime expiredAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** A shortfall carried against future earnings, not a real balance. */
    public boolean isDebt() {
        return expiresAt == null;
    }

    public boolean isExpired() {
        return expiredAt != null;
    }
}
