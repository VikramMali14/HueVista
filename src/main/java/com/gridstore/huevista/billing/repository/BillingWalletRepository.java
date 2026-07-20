package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.BillingWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BillingWalletRepository extends JpaRepository<BillingWallet, String> {

    Optional<BillingWallet> findByUserId(String userId);

    /**
     * Atomically spend {@code amountPaise} only while the balance covers it — a single
     * conditional UPDATE (no read-modify-write in Java), so two concurrent purchases
     * can never overdraw the wallet. Returns 1 when the debit landed, 0 when the
     * balance was insufficient.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BillingWallet w SET w.balancePaise = w.balancePaise - :amountPaise " +
           "WHERE w.userId = :userId AND w.balancePaise >= :amountPaise")
    int debitIfSufficient(@Param("userId") String userId, @Param("amountPaise") long amountPaise);

    /** Atomically add a verified top-up to the balance. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BillingWallet w SET w.balancePaise = w.balancePaise + :amountPaise " +
           "WHERE w.userId = :userId")
    int credit(@Param("userId") String userId, @Param("amountPaise") long amountPaise);
}
