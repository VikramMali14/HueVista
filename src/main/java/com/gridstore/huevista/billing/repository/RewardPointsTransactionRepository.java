package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardPointsTransactionRepository extends JpaRepository<RewardPointsTransaction, String> {

    List<RewardPointsTransaction> findTop20ByUserIdOrderByCreatedAtDesc(String userId);
}
