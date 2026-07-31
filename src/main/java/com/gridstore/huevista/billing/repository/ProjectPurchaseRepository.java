package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.ProjectPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectPurchaseRepository extends JpaRepository<ProjectPurchase, String> {

    boolean existsByPaymentId(String paymentId);
}
