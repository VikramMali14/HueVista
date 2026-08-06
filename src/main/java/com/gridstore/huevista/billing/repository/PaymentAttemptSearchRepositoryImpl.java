package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.PaymentAttempt;
import com.gridstore.huevista.billing.model.PaymentAttemptStatus;
import com.gridstore.huevista.billing.model.PaymentFlow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Criteria implementation of {@link PaymentAttemptSearchRepository}. Spring Data finds
 * this by name — the {@code Impl} suffix on the fragment interface — and mixes it into
 * {@link PaymentAttemptRepository}.
 */
public class PaymentAttemptSearchRepositoryImpl implements PaymentAttemptSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<PaymentAttempt> search(PaymentAttemptStatus status,
                                       PaymentFlow flow,
                                       String userId,
                                       LocalDateTime from,
                                       LocalDateTime to,
                                       String q,
                                       int offset,
                                       int limit) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<PaymentAttempt> query = cb.createQuery(PaymentAttempt.class);
        Root<PaymentAttempt> a = query.from(PaymentAttempt.class);

        List<Predicate> filters = new ArrayList<>();
        if (status != null) filters.add(cb.equal(a.get("status"), status));
        if (flow != null) filters.add(cb.equal(a.get("flow"), flow));
        if (userId != null) filters.add(cb.equal(a.get("userId"), userId));
        if (from != null) filters.add(cb.greaterThanOrEqualTo(a.get("createdAt"), from));
        if (to != null) filters.add(cb.lessThanOrEqualTo(a.get("createdAt"), to));

        if (q != null && !q.isBlank()) {
            // Lower-cased on both sides so the match is case-insensitive whatever the
            // column collation is. The wildcards go on the value, not the pattern
            // string, so a term containing % or _ is matched literally rather than
            // silently behaving as a wildcard of the admin's own making.
            String term = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
            filters.add(cb.or(
                    cb.like(cb.lower(a.get("userEmail")), term, '\\'),
                    cb.like(cb.lower(a.get("reference")), term, '\\'),
                    cb.like(cb.lower(a.get("paymentId")), term, '\\'),
                    cb.like(cb.lower(a.get("pageUrl")), term, '\\')));
        }

        query.select(a)
             // id breaks ties so paging is stable: attempts recorded in the same
             // millisecond must not swap places between page 1 and page 2.
             .orderBy(cb.desc(a.get("createdAt")), cb.desc(a.get("id")));
        if (!filters.isEmpty()) {
            query.where(cb.and(filters.toArray(new Predicate[0])));
        }

        return entityManager.createQuery(query)
                .setFirstResult(Math.max(0, offset))
                .setMaxResults(Math.max(1, limit))
                .getResultList();
    }

    /** Neutralise the LIKE metacharacters so a search term is matched as typed. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
