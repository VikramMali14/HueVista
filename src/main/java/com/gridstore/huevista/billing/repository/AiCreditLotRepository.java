package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.AiCreditLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiCreditLotRepository extends JpaRepository<AiCreditLot, String> {

    /**
     * One account's unspent batches, soonest to lapse first.
     *
     * <p>The ordering is the whole product rule: a spend eats the batch that dies first, so
     * a customer never watches dated credits lapse while credits they bought later sit
     * unspent. Nulls last puts the never-expiring ones at the back, where they belong —
     * they are the batch that can afford to wait.
     */
    @Query("""
           SELECT l FROM AiCreditLot l
            WHERE l.userId = :userId
              AND l.expiredAt IS NULL
              AND l.creditsRemaining > 0
            ORDER BY CASE WHEN l.expiresAt IS NULL THEN 1 ELSE 0 END, l.expiresAt ASC, l.createdAt ASC
           """)
    List<AiCreditLot> findSpendable(@Param("userId") String userId);

    /**
     * Take {@code credits} out of one batch, but only if they are still in it.
     *
     * <p>A compare-and-set for the same reason the wallet debit is one: two renders
     * starting at once must not both draw the last credit out of the same batch and leave a
     * negative remainder behind. The caller walks the list and moves on when this returns 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE AiCreditLot l
              SET l.creditsRemaining = l.creditsRemaining - :credits
            WHERE l.id = :id
              AND l.expiredAt IS NULL
              AND l.creditsRemaining >= :credits
           """)
    int drawDown(@Param("id") String id, @Param("credits") int credits);

    /** Batches whose date has passed and that still hold something to write off. */
    @Query("""
           SELECT l FROM AiCreditLot l
            WHERE l.expiredAt IS NULL
              AND l.expiresAt IS NOT NULL
              AND l.expiresAt <= :now
              AND l.creditsRemaining > 0
            ORDER BY l.expiresAt ASC
           """)
    List<AiCreditLot> findDue(@Param("now") LocalDateTime now);

    /**
     * Write off a batch, but only if nothing has spent from it since it was read.
     *
     * <p>The remainder travels in the WHERE clause so a render that spent from this batch
     * between the sweep reading it and stamping it loses the race rather than being charged
     * twice — the sweep simply finds it again tomorrow, or finds it already empty.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE AiCreditLot l
              SET l.creditsRemaining = 0, l.expiredAt = :now
            WHERE l.id = :id
              AND l.expiredAt IS NULL
              AND l.creditsRemaining = :expected
           """)
    int expire(@Param("id") String id, @Param("expected") int expected, @Param("now") LocalDateTime now);
}
