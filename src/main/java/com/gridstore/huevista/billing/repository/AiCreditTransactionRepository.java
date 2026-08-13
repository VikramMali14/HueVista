package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.AiCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiCreditTransactionRepository extends JpaRepository<AiCreditTransaction, String> {

    /** The statement, newest first. Bounded because a wallet panel shows recent movement,
     *  not a lifetime — the same shape the point statement uses. */
    List<AiCreditTransaction> findTop20ByUserIdOrderByCreatedAtDesc(String userId);
}
