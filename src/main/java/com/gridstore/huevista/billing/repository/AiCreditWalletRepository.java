package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.AiCreditWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiCreditWalletRepository extends JpaRepository<AiCreditWallet, String> {

    Optional<AiCreditWallet> findByUserId(String userId);

    /**
     * Take {@code credits} out of a wallet, but only if they are actually there.
     *
     * A compare-and-set rather than a read-then-write, for the same reason
     * {@code claimTrialProjectSlot} is one: two browser tabs on the render screen both read
     * "1 credit left", both pass a Java-side check, and both start a paid model call. Here
     * the WHERE clause is the check, so exactly one UPDATE touches a row and the other gets
     * 0 back and is refused.
     *
     * @return 1 when the credits were taken, 0 when the balance was short (or there is no
     *         wallet yet, which is the same thing to the caller)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE AiCreditWallet w
              SET w.balance = w.balance - :credits
            WHERE w.userId = :userId
              AND w.balance >= :credits
           """)
    int spendIfAvailable(@Param("userId") String userId, @Param("credits") int credits);

    /**
     * Put credits into an existing wallet.
     *
     * Also an UPDATE rather than a save of a read entity, so a purchase verified at the
     * same moment a render is spending cannot overwrite the other's balance with a stale
     * one. Returns 0 when there is no wallet yet — the caller opens one and retries.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE AiCreditWallet w
              SET w.balance = w.balance + :credits
            WHERE w.userId = :userId
           """)
    int addCredits(@Param("userId") String userId, @Param("credits") int credits);
}
