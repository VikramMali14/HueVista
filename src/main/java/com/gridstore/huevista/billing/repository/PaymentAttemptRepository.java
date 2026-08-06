package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String> {

    Optional<PaymentAttempt> findByReference(String reference);

    /**
     * The admin report's one query. Every filter is optional and switched off by
     * passing null, which keeps this to a single statement instead of a Specification
     * tree — there are only six filters and they never combine in interesting ways.
     *
     * <p>{@code q} matches the free-text fields an admin actually has to hand when
     * someone reports a problem: the email they signed up with, the reference their
     * bank statement shows, the payment id from a Razorpay dashboard, or a fragment of
     * the URL they were on.
     */
    @Query("""
            SELECT a FROM PaymentAttempt a
            WHERE (:status IS NULL OR a.status = :status)
              AND (:flow IS NULL OR a.flow = :flow)
              AND (:userId IS NULL OR a.userId = :userId)
              AND (:from IS NULL OR a.createdAt >= :from)
              AND (:to IS NULL OR a.createdAt <= :to)
              AND (:q IS NULL OR LOWER(a.userEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(a.reference) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(a.paymentId) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(a.pageUrl)   LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    Page<PaymentAttempt> search(@Param("status") PaymentAttemptStatus status,
                                @Param("flow") PaymentFlow flow,
                                @Param("userId") String userId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("q") String q,
                                Pageable pageable);

    /** Counts per status over a window — the report's headline tiles. */
    @Query("""
            SELECT a.status, COUNT(a), COALESCE(SUM(a.amountPaise), 0)
            FROM PaymentAttempt a
            WHERE (:from IS NULL OR a.createdAt >= :from)
            GROUP BY a.status
            """)
    List<Object[]> countByStatusSince(@Param("from") LocalDateTime from);

    /** Counts per flow over a window, so a broken button shows up as one bad flow. */
    @Query("""
            SELECT a.flow, COUNT(a), COALESCE(SUM(a.amountPaise), 0)
            FROM PaymentAttempt a
            WHERE (:from IS NULL OR a.createdAt >= :from)
            GROUP BY a.flow
            """)
    List<Object[]> countByFlowSince(@Param("from") LocalDateTime from);

    /**
     * Where abandonment happens, worst page first. A single URL dominating this list
     * is the report earning its keep: it means that page, not the buyers, is the bug.
     */
    @Query("""
            SELECT a.pageUrl, COUNT(a), COALESCE(SUM(a.amountPaise), 0)
            FROM PaymentAttempt a
            WHERE a.pageUrl IS NOT NULL
              AND a.status IN (com.gridstore.huevista.billing.model.PaymentAttemptStatus.ABANDONED,
                               com.gridstore.huevista.billing.model.PaymentAttemptStatus.FAILED,
                               com.gridstore.huevista.billing.model.PaymentAttemptStatus.VERIFY_FAILED)
              AND (:from IS NULL OR a.createdAt >= :from)
            GROUP BY a.pageUrl
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> abandonmentByPageSince(@Param("from") LocalDateTime from, Pageable pageable);

    /** Gateway decline reasons, worst first — tells a bank problem from a buyer one. */
    @Query("""
            SELECT COALESCE(a.errorCode, 'UNKNOWN'), COALESCE(a.errorDescription, ''), COUNT(a)
            FROM PaymentAttempt a
            WHERE a.status = com.gridstore.huevista.billing.model.PaymentAttemptStatus.FAILED
              AND (:from IS NULL OR a.createdAt >= :from)
            GROUP BY a.errorCode, a.errorDescription
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> failureReasonsSince(@Param("from") LocalDateTime from, Pageable pageable);

    /**
     * Attempts left open past a cutoff — the sweeper's input. An attempt nobody ever
     * reported back on is itself a finding (the browser died, or our own event call is
     * broken), so they are closed as ABANDONED rather than left to look "in progress"
     * forever.
     */
    @Query("""
            SELECT a FROM PaymentAttempt a
            WHERE a.status IN (com.gridstore.huevista.billing.model.PaymentAttemptStatus.CREATED,
                               com.gridstore.huevista.billing.model.PaymentAttemptStatus.OPENED)
              AND a.createdAt < :cutoff
            """)
    List<PaymentAttempt> findStaleOpen(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** Newest attempts for one user — the "what did this shop try?" view in support. */
    List<PaymentAttempt> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
}
