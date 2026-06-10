package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.ProjectCreditPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectCreditPaymentRepository extends JpaRepository<ProjectCreditPayment, String> {

    boolean existsByPaymentId(String paymentId);
}
