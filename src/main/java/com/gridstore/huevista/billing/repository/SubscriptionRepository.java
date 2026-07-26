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

    /**
     * The subscriptions that currently ENTITLE a user, best first.
     *
     * ACTIVE is the normal case. A CANCELLED plan whose paid period has not yet elapsed
     * also entitles: cancelling sets {@code cancel_at_cycle_end} at the gateway, the
     * customer keeps what they paid for until the cycle actually ends, and the UI says
     * exactly that. Matching on ACTIVE alone meant the moment Razorpay echoed
     * {@code subscription.cancelled} every feature returned "No active subscription"
     * while the account page still (correctly) read "active till period end".
     *
     * Ordered so a genuinely ACTIVE row always wins over a winding-down CANCELLED one.
     */
    @Query("""
            SELECT s FROM Subscription s
             WHERE s.user.id = :userId
               AND (s.status = :active
                    OR (s.status = :cancelled AND s.currentPeriodEnd IS NOT NULL
                        AND s.currentPeriodEnd > :now))
             ORDER BY CASE WHEN s.status = :active THEN 0 ELSE 1 END, s.createdAt DESC
            """)
    List<Subscription> findEntitling(@Param("userId") String userId,
                                     @Param("active") SubscriptionStatus active,
                                     @Param("cancelled") SubscriptionStatus cancelled,
                                     @Param("now") LocalDateTime now);

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
           "WHERE s.id = :id AND s.aiGenerationsUsed + s.reservedImages " +
           "      < s.aiGenerationsLimit + s.purchasedImageCredits")
    int incrementAiUsageIfWithinLimit(@Param("id") String id);

    /**
     * Atomically HOLD {@code count} images at once (used when a retailer assigns a
     * multi-project access code — one image held per assigned project). The single
     * conditional UPDATE only applies when the WHOLE block fits under the effective
     * allowance, so a partial reservation is impossible. Returns 1 when the block was
     * held, 0 when it wouldn't fit.
     *
     * A hold is NOT a charge: it moves into {@code aiGenerationsUsed} via
     * {@link #consumeReservedImage} when the project is actually segmented, or back
     * into the pool via {@link #releaseReservedImages} when the code is revoked or
     * expires unredeemed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedImages = s.reservedImages + :count " +
           "WHERE s.id = :id AND s.aiGenerationsUsed + s.reservedImages + :count " +
           "      <= s.aiGenerationsLimit + s.purchasedImageCredits")
    int reserveImagesIfWithinLimit(@Param("id") String id, @Param("count") int count);

    /**
     * Return {@code count} previously held images to the pool (code revoked, or expired
     * without being redeemed). Floored at zero and applied atomically.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedImages = " +
           "  CASE WHEN s.reservedImages > :count THEN s.reservedImages - :count ELSE 0 END " +
           "WHERE s.id = :id AND s.reservedImages > 0")
    int releaseReservedImages(@Param("id") String id, @Param("count") int count);

    /**
     * Spend one HELD image: moves it from {@code reservedImages} into
     * {@code aiGenerationsUsed} in a single atomic UPDATE, so an access-code project
     * that was already paid for at generation time is never charged a second time.
     * Returns 1 when a hold was consumed, 0 when none was left (caller should fall
     * back to a normal charge — e.g. a legacy code issued before holds existed).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedImages = s.reservedImages - 1, " +
           "       s.aiGenerationsUsed = s.aiGenerationsUsed + 1 " +
           "WHERE s.id = :id AND s.reservedImages > 0")
    int consumeReservedImage(@Param("id") String id);

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

    /**
     * Atomically claim one of the trial's project slots. Monotonic and conditional, so
     * neither deleting a project nor firing parallel creates can recycle the allowance.
     * Returns 1 when a slot was taken, 0 when the trial allowance is spent.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.trialProjectsCreated = s.trialProjectsCreated + 1 " +
           "WHERE s.id = :id AND s.trialProjectsCreated < :limit")
    int claimTrialProjectSlot(@Param("id") String id, @Param("limit") int limit);

    @Query("SELECT s.plan, COUNT(s) FROM Subscription s WHERE s.status = :status GROUP BY s.plan")
    List<Object[]> countByPlanAndStatus(@Param("status") SubscriptionStatus status);

    @Query("SELECT COALESCE(SUM(s.aiGenerationsUsed), 0) FROM Subscription s WHERE s.status = :status")
    long sumAiGenerationsUsedByStatus(@Param("status") SubscriptionStatus status);
}
