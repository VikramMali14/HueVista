package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.AiCreditPurchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiCreditPurchaseRepository extends JpaRepository<AiCreditPurchase, String> {

    /** The replay check. The unique constraint on the column is the race-safe backstop. */
    boolean existsByPaymentId(String paymentId);
}
