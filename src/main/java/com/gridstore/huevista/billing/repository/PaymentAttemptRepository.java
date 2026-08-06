package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String>,
        PaymentAttemptSearchRepository {

    Optional<PaymentAttempt> findByReference(String reference);

    /**
     * Counts per status over a window — the report's headline tiles.
     *
     * <p>The {@code CAST} is not decoration. {@code :from} is null for the all-time
     * window, and PostgreSQL refuses to prepare a statement whose parameter type it
     * cannot infer: a bare {@code ? IS NULL} gives it nothing to infer from, and the
     * whole report 500s with "could not determine data type of parameter $1". Naming
     * the type in the SQL settles it. H2 — what the tests run on — infers the type
     * happily either way, so nothing here fails until it reaches production.
     */
    @Query("""
            SELECT a.status, COUNT(a), COALESCE(SUM(a.amountPaise), 0)
            FROM PaymentAttempt a
            WHERE (CAST(:from AS LocalDateTime) IS NULL OR a.createdAt >= :from)
            GROUP BY a.status
            """)
    List<Object[]> countByStatusSince(@Param("from") LocalDateTime from);

    /** Counts per flow over a window, so a broken button shows up as one bad flow. */
    @Query("""
            SELECT a.flow, COUNT(a), COALESCE(SUM(a.amountPaise), 0)
            FROM PaymentAttempt a
            WHERE (CAST(:from AS LocalDateTime) IS NULL OR a.createdAt >= :from)
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
              AND (CAST(:from AS LocalDateTime) IS NULL OR a.createdAt >= :from)
            GROUP BY a.pageUrl
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> abandonmentByPageSince(@Param("from") LocalDateTime from, Pageable pageable);

    /** Gateway decline reasons, worst first — tells a bank problem from a buyer one. */
    @Query("""
            SELECT COALESCE(a.errorCode, 'UNKNOWN'), COALESCE(a.errorDescription, ''), COUNT(a)
            FROM PaymentAttempt a
            WHERE a.status = com.gridstore.huevista.billing.model.PaymentAttemptStatus.FAILED
              AND (CAST(:from AS LocalDateTime) IS NULL OR a.createdAt >= :from)
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
