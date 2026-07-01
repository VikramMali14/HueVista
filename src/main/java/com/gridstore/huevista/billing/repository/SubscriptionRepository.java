package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findTopByUserIdAndStatusOrderByCreatedAtDesc(String userId, SubscriptionStatus status);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    List<Subscription> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Subscription> findByUserIdAndStatus(String userId, SubscriptionStatus status);

    boolean existsByUserIdAndStatus(String userId, SubscriptionStatus status);

    // A paid subscription blocks creating another; a free trial does NOT — a trialing
    // retailer must be able to upgrade to a plan (trials have trial = true).
    boolean existsByUserIdAndStatusAndTrialFalse(String userId, SubscriptionStatus status);

    List<Subscription> findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus status, LocalDateTime cutoff);

    long countByStatus(SubscriptionStatus status);

    /**
     * Atomically charge one AI generation only while usage is below the limit. A single
     * conditional UPDATE (no read-modify-write in Java) so two concurrent requests can't
     * both consume the last remaining credit. Returns the number of rows updated: 1 when
     * a credit was taken, 0 when the limit was already reached.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.aiGenerationsUsed = s.aiGenerationsUsed + 1 " +
           "WHERE s.id = :id AND s.aiGenerationsUsed < s.aiGenerationsLimit")
    int incrementAiUsageIfWithinLimit(@Param("id") String id);

    /**
     * Atomically charge one AI generation regardless of the limit — used when the work has
     * already succeeded, so the charge must land even if it nudges usage to the ceiling.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.aiGenerationsUsed = s.aiGenerationsUsed + 1 WHERE s.id = :id")
    int incrementAiUsage(@Param("id") String id);

    /** Atomically return one previously reserved credit (never below zero) when the work failed. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.aiGenerationsUsed = s.aiGenerationsUsed - 1 " +
           "WHERE s.id = :id AND s.aiGenerationsUsed > 0")
    int decrementAiUsage(@Param("id") String id);

    @Query("SELECT s.plan, COUNT(s) FROM Subscription s WHERE s.status = :status GROUP BY s.plan")
    List<Object[]> countByPlanAndStatus(@Param("status") SubscriptionStatus status);

    @Query("SELECT COALESCE(SUM(s.aiGenerationsUsed), 0) FROM Subscription s WHERE s.status = :status")
    long sumAiGenerationsUsedByStatus(@Param("status") SubscriptionStatus status);
}
