package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.RewardPointsLot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RewardPointsLotRepository extends JpaRepository<RewardPointsLot, String> {

    /**
     * Spendable balance: live lots plus any outstanding debt (which is negative, so it
     * subtracts). Expired and written-off lots are excluded.
     */
    @Query("""
            SELECT COALESCE(SUM(l.pointsRemaining), 0) FROM RewardPointsLot l
             WHERE l.userId = :userId AND l.expiredAt IS NULL
               AND (l.expiresAt IS NULL OR l.expiresAt > :now)
            """)
    int balance(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /**
     * Every lot a mutation might touch, row-locked and soonest-expiry first so spending
     * drains the points closest to dying. Debt lots (null expiry) sort last under
     * NULLS LAST and are skipped by the spend loop, which only draws from positive lots.
     *
     * Taking the lock over the whole set serialises credit / spend / reverse per shop,
     * which is what keeps two concurrent spends from both passing the balance check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT l FROM RewardPointsLot l
             WHERE l.userId = :userId AND l.expiredAt IS NULL
               AND (l.expiresAt IS NULL OR l.expiresAt > :now)
             ORDER BY l.expiresAt ASC NULLS LAST
            """)
    List<RewardPointsLot> lockLiveLots(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /** Live lots for the statement — read-only, no lock. */
    @Query("""
            SELECT l FROM RewardPointsLot l
             WHERE l.userId = :userId AND l.expiredAt IS NULL AND l.pointsRemaining > 0
               AND l.expiresAt IS NOT NULL AND l.expiresAt > :now
             ORDER BY l.expiresAt ASC
            """)
    List<RewardPointsLot> liveLots(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /**
     * Lots with unspent points whose expiry falls in a window and which have not had the
     * given notice sent yet. Drives both the 10-day warning and the last-day notice; the
     * caller passes the window and checks the right flag.
     */
    @Query("""
            SELECT l FROM RewardPointsLot l
             WHERE l.expiredAt IS NULL AND l.pointsRemaining > 0
               AND l.expiresAt IS NOT NULL
               AND l.expiresAt >= :from AND l.expiresAt < :to
             ORDER BY l.userId, l.expiresAt
            """)
    List<RewardPointsLot> expiringBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Lots that have run out of time and still hold points — the sweep's input. */
    @Query("""
            SELECT l FROM RewardPointsLot l
             WHERE l.expiredAt IS NULL AND l.pointsRemaining > 0
               AND l.expiresAt IS NOT NULL AND l.expiresAt <= :now
            """)
    List<RewardPointsLot> dueForExpiry(@Param("now") LocalDateTime now);
}
