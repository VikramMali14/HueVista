package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, String> {
}
