package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findTopByUserIdAndStatusOrderByCreatedAtDesc(String userId, SubscriptionStatus status);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    List<Subscription> findByUserIdOrderByCreatedAtDesc(String userId);

    boolean existsByUserIdAndStatus(String userId, SubscriptionStatus status);

    long countByStatus(SubscriptionStatus status);

    @Query("SELECT s.plan, COUNT(s) FROM Subscription s WHERE s.status = :status GROUP BY s.plan")
    List<Object[]> countByPlanAndStatus(@Param("status") SubscriptionStatus status);

    @Query("SELECT COALESCE(SUM(s.aiGenerationsUsed), 0) FROM Subscription s WHERE s.status = :status")
    long sumAiGenerationsUsedByStatus(@Param("status") SubscriptionStatus status);
}
