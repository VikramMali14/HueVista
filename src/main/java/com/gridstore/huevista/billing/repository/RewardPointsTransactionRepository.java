package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardPointsTransactionRepository extends JpaRepository<RewardPointsTransaction, String> {

    List<RewardPointsTransaction> findTop20ByUserIdOrderByCreatedAtDesc(String userId);

    /** Move the point ledger's entries with the lots they explain, for an account merge. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "UPDATE RewardPointsTransaction t SET t.userId = :toUserId WHERE t.userId = :fromUserId")
    int reassignOwner(@org.springframework.data.repository.query.Param("fromUserId") String fromUserId,
                      @org.springframework.data.repository.query.Param("toUserId") String toUserId);
}
