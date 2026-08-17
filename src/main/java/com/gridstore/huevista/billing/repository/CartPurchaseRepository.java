package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.CartPurchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CartPurchaseRepository extends JpaRepository<CartPurchase, String> {

    /** The readable half of the replay guard; the unique constraint is the safe half. */
    boolean existsByPaymentId(String paymentId);
}
