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
     * Atomically charge one image only while usage is below the effective allowance
     * (monthly limit + purchased pay-per-image credits). A single conditional UPDATE
     * (no read-modify-write in Java) so two concurrent requests can't both consume the
     * last remaining credit. Returns the number of rows updated: 1 when a credit was
     * taken, 0 when the allowance was already reached.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.aiGenerationsUsed = s.aiGenerationsUsed + 1 " +
           "WHERE s.id = :id AND s.aiGenerationsUsed < s.aiGenerationsLimit + s.purchasedImageCredits")
    int incrementAiUsageIfWithinLimit(@Param("id") String id);

    /**
     * Atomically charge one AI auto-mask run while usage is below the effective
     * allowance (plan limit + purchased pay-per-use credits) — same conditional-UPDATE
     * pattern as the image quota. Returns 1 when charged, 0 when the allowance is spent.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.autoMasksUsed = s.autoMasksUsed + 1 " +
           "WHERE s.id = :id AND s.autoMasksUsed < s.autoMasksLimit + s.purchasedAutoMaskCredits")
    int incrementAutoMaskUsageIfWithinLimit(@Param("id") String id);

    /** Add pay-per-use auto-mask credits after a verified wallet debit (Rs. 25 + GST each). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.purchasedAutoMaskCredits = s.purchasedAutoMaskCredits + :count " +
           "WHERE s.id = :id")
    int addPurchasedAutoMaskCredits(@Param("id") String id, @Param("count") int count);

    /** Atomically charge one auto-mask run regardless of the limit — the run already happened. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.autoMasksUsed = s.autoMasksUsed + 1 WHERE s.id = :id")
    int incrementAutoMaskUsage(@Param("id") String id);

    /** Add pay-per-image overage credits after a verified Rs. 50 + GST payment. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.purchasedImageCredits = s.purchasedImageCredits + :count " +
           "WHERE s.id = :id")
    int addPurchasedImageCredits(@Param("id") String id, @Param("count") int count);

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

    /**
     * Atomically charge one colour-board PDF download while usage is below the limit —
     * same conditional-UPDATE pattern as {@link #incrementAiUsageIfWithinLimit} so
     * parallel downloads can't both take the last one. Returns 1 when charged, 0 when
     * the monthly allowance is spent.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.pdfDownloadsUsed = s.pdfDownloadsUsed + 1 " +
           "WHERE s.id = :id AND s.pdfDownloadsUsed < s.pdfDownloadsLimit")
    int incrementPdfUsageIfWithinLimit(@Param("id") String id);

    @Query("SELECT s.plan, COUNT(s) FROM Subscription s WHERE s.status = :status GROUP BY s.plan")
    List<Object[]> countByPlanAndStatus(@Param("status") SubscriptionStatus status);

    @Query("SELECT COALESCE(SUM(s.aiGenerationsUsed), 0) FROM Subscription s WHERE s.status = :status")
    long sumAiGenerationsUsedByStatus(@Param("status") SubscriptionStatus status);
}
