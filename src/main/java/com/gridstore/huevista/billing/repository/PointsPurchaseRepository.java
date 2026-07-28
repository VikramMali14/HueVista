package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.PointsPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointsPurchaseRepository extends JpaRepository<PointsPurchase, String> {

    boolean existsByPaymentId(String paymentId);
}
