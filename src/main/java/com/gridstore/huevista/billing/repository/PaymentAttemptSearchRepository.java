package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The admin payment report's one query, hand-built rather than annotated.
 *
 * <p>It was a single JPQL statement with every filter guarded by
 * {@code (:param IS NULL OR ...)}. That reads well and does not work: on PostgreSQL a
 * null bind whose type the server cannot infer fails at prepare time, so the report
 * returned 500 for the ordinary case of an admin opening it with no search term
 * ("function lower(bytea) does not exist"). Building the predicates in code means a
 * filter nobody asked for contributes no parameter at all, which is both the fix and
 * the faster query — PostgreSQL can use an index on {@code created_at} instead of
 * planning around a disjunction it cannot see through.
 */
public interface PaymentAttemptSearchRepository {

    /**
     * Attempts matching every filter given, newest first. Any argument may be null,
     * meaning "do not filter on this".
     *
     * <p>{@code q} matches the free-text fields an admin actually has to hand when
     * someone reports a problem: the email they signed up with, the reference their
     * bank statement shows, the payment id from a Razorpay dashboard, or a fragment of
     * the URL they were on.
     *
     * @param offset rows to skip, never negative
     * @param limit  maximum rows to return, always positive
     */
    List<PaymentAttempt> search(PaymentAttemptStatus status,
                                PaymentFlow flow,
                                String userId,
                                LocalDateTime from,
                                LocalDateTime to,
                                String q,
                                int offset,
                                int limit);
}
