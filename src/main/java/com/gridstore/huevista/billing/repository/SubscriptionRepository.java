package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findTopByUserIdAndStatusOrderByCreatedAtDesc(String userId, SubscriptionStatus status);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    List<Subscription> findByUserIdOrderByCreatedAtDesc(String userId);

    boolean existsByUserIdAndStatus(String userId, SubscriptionStatus status);
}
