package com.gridstore.huevista.billing.repository;

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
     * A subscription whose period has not STARTED yet never entitles, whatever its
     * status. Re-subscribing while a paid plan winds down schedules the new plan at the
     * gateway for the day the old one ends (so the customer isn't billed twice); until
     * that day the old plan is the one in force, and the new one must not hand out its
     * larger quota a month early.
     *
     * Ordered so a genuinely ACTIVE row always wins over a winding-down CANCELLED one.
     */
    @Query("""
            SELECT s FROM Subscription s
             WHERE s.user.id = :userId
               AND (s.currentPeriodStart IS NULL OR s.currentPeriodStart <= :now)
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
     * Atomically charge one project only while usage is below the effective allowance
     * (monthly limit + purchased extras + credits carried in from a replaced plan). A
     * single conditional UPDATE (no read-modify-write in Java) so two concurrent
     * requests can't both consume the last remaining credit. Returns the number of rows
     * updated: 1 when a credit was taken, 0 when the allowance was already reached.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.projectsUsed = s.projectsUsed + 1 " +
           "WHERE s.id = :id AND s.projectsUsed + s.reservedProjects " +
           "      < s.projectsLimit + s.purchasedProjectCredits + s.carriedProjectCredits")
    int incrementProjectUsageIfWithinLimit(@Param("id") String id);

    /**
     * Atomically HOLD {@code count} projects at once (used when a retailer assigns a
     * multi-project access code — one held per assigned project). The single
     * conditional UPDATE only applies when the WHOLE block fits under the effective
     * allowance, so a partial reservation is impossible. Returns 1 when the block was
     * held, 0 when it wouldn't fit.
     *
     * A hold is NOT a charge: it moves into {@code projectsUsed} via
     * {@link #consumeReservedProject} when the project is actually segmented, or back
     * into the pool via {@link #releaseReservedProjects} when the code is revoked or
     * expires unredeemed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedProjects = s.reservedProjects + :count " +
           "WHERE s.id = :id AND s.projectsUsed + s.reservedProjects + :count " +
           "      <= s.projectsLimit + s.purchasedProjectCredits + s.carriedProjectCredits")
    int reserveProjectsIfWithinLimit(@Param("id") String id, @Param("count") int count);

    /**
     * Add {@code count} holds unconditionally — the carry-over when a new plan supersedes
     * an old one. Unlike {@link #reserveProjectsIfWithinLimit} this is NOT limit-gated:
     * the holds already exist behind issued access codes, so refusing to move them would
     * strand the codes rather than protect the quota. (Downgrades can therefore land
     * slightly over the new plan's ceiling until those codes are spent, which is the
     * correct end of the trade — the shop already paid for them.)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedProjects = s.reservedProjects + :count WHERE s.id = :id")
    int addReservedProjects(@Param("id") String id, @Param("count") int count);

    /**
     * Return {@code count} previously held projects to the pool (code revoked, or expired
     * without being redeemed). Floored at zero and applied atomically.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedProjects = " +
           "  CASE WHEN s.reservedProjects > :count THEN s.reservedProjects - :count ELSE 0 END " +
           "WHERE s.id = :id AND s.reservedProjects > 0")
    int releaseReservedProjects(@Param("id") String id, @Param("count") int count);

    /**
     * Spend one HELD project: moves it from {@code reservedProjects} into
     * {@code projectsUsed} in a single atomic UPDATE, so an access-code project
     * that was already paid for at generation time is never charged a second time.
     * Returns 1 when a hold was consumed, 0 when none was left (caller should fall
     * back to a normal charge — e.g. a legacy code issued before holds existed).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.reservedProjects = s.reservedProjects - 1, " +
           "       s.projectsUsed = s.projectsUsed + 1 " +
           "WHERE s.id = :id AND s.reservedProjects > 0")
    int consumeReservedProject(@Param("id") String id);

    /** Add extra projects bought at the plan's own rate, in points or in money. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.purchasedProjectCredits = s.purchasedProjectCredits + :count " +
           "WHERE s.id = :id")
    int addPurchasedProjectCredits(@Param("id") String id, @Param("count") int count);

    /**
     * Add projects left over from a plan this one replaces. Separate from
     * {@link #addPurchasedProjectCredits} because these expire with the cycle: they are
     * someone's unused monthly allowance, not something they bought outright.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Subscription s SET s.carriedProjectCredits = s.carriedProjectCredits + :count " +
           "WHERE s.id = :id")
    int addCarriedProjectCredits(@Param("id") String id, @Param("count") int count);

    /**
     * Atomically charge one project regardless of the limit — used when the work has
     * already succeeded, so the charge must land even if it nudges usage to the ceiling.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.projectsUsed = s.projectsUsed + 1 WHERE s.id = :id")
    int incrementProjectUsage(@Param("id") String id);

    /** Atomically return one previously reserved credit (never below zero) when the work failed. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Subscription s SET s.projectsUsed = s.projectsUsed - 1 " +
           "WHERE s.id = :id AND s.projectsUsed > 0")
    int decrementProjectUsage(@Param("id") String id);

    /**
     * Atomically charge one colour-board PDF download while usage is below the limit —
     * same conditional-UPDATE pattern as {@link #incrementProjectUsageIfWithinLimit} so
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

    /**
     * Rows that still hold something the shop paid for, other than {@code keepId}.
     *
     * Used when a plan goes live to sweep up credits stranded on subscriptions that ENDED
     * rather than being superseded. Those rows are invisible to every other query — the
     * entitlement lookup only sees ACTIVE and winding-down CANCELLED ones — so a shop that
     * bought extras, let the plan lapse and came back a month later lost them, and any
     * access codes still outstanding lost the holds standing behind them.
     */
    @Query("SELECT s FROM Subscription s WHERE s.user.id = :userId AND s.id <> :keepId "
           + "AND (s.purchasedProjectCredits > 0 OR s.reservedProjects > 0)")
    List<Subscription> findWithUnspentCredits(@Param("userId") String userId,
                                              @Param("keepId") String keepId);

    @Query("SELECT s.plan, COUNT(s) FROM Subscription s WHERE s.status = :status GROUP BY s.plan")
    List<Object[]> countByPlanAndStatus(@Param("status") SubscriptionStatus status);

    @Query("SELECT COALESCE(SUM(s.projectsUsed), 0) FROM Subscription s WHERE s.status = :status")
    long sumProjectsUsedByStatus(@Param("status") SubscriptionStatus status);
}
