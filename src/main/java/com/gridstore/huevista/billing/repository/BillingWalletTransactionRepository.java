package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.BillingWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingWalletTransactionRepository
        extends JpaRepository<BillingWalletTransaction, String> {

    List<BillingWalletTransaction> findTop20ByUserIdOrderByCreatedAtDesc(String userId);
}
