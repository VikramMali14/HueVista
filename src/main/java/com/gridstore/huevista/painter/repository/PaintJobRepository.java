package com.gridstore.huevista.painter.repository;

import com.gridstore.huevista.painter.model.PaintJob;
import com.gridstore.huevista.painter.model.PaintJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaintJobRepository extends JpaRepository<PaintJob, String> {

    // Fetch-joined list queries: PaintJobResponse reads project/retailer/customer/
    // painter from every row, so without the JOIN FETCH each job triggers up to
    // four extra lazy-load SELECTs (a classic N+1). Pageable bounds the result.

    @Query("SELECT j FROM PaintJob j " +
           "JOIN FETCH j.project JOIN FETCH j.retailer JOIN FETCH j.customer JOIN FETCH j.painter " +
           "WHERE j.painter.id = :painterId ORDER BY j.createdAt DESC")
    List<PaintJob> findForPainterWithDetails(@Param("painterId") String painterId, Pageable pageable);

    @Query("SELECT j FROM PaintJob j " +
           "JOIN FETCH j.project JOIN FETCH j.retailer JOIN FETCH j.customer LEFT JOIN FETCH j.painter " +
           "WHERE j.retailer.id = :retailerId ORDER BY j.createdAt DESC")
    List<PaintJob> findForRetailerWithDetails(@Param("retailerId") String retailerId, Pageable pageable);

    @Query("SELECT j FROM PaintJob j " +
           "JOIN FETCH j.project JOIN FETCH j.retailer JOIN FETCH j.customer LEFT JOIN FETCH j.painter " +
           "WHERE j.customer.id = :customerId ORDER BY j.createdAt DESC")
    List<PaintJob> findForCustomerWithDetails(@Param("customerId") String customerId, Pageable pageable);

    List<PaintJob> findByPainterIdAndStatusInOrderByCreatedAtDesc(String painterId, List<PaintJobStatus> statuses);

    Optional<PaintJob> findByProjectId(String projectId);

    /**
     * Cancel every unfinished job a painter holds for one shop, in a single UPDATE.
     * Used when the shop ends the relationship: leaving open jobs assigned let a removed
     * painter keep working (and keep reading the customer's site address) for a shop that
     * had already cut them off. COMPLETED and already-CANCELLED jobs are untouched.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PaintJob j SET j.status = :cancelled, j.declineReason = :reason
             WHERE j.painter.id = :painterId AND j.retailer.id = :retailerId
               AND j.status IN :openStatuses
            """)
    int cancelOpenJobsForPainter(@Param("painterId") String painterId,
                                 @Param("retailerId") String retailerId,
                                 @Param("openStatuses") List<PaintJobStatus> openStatuses,
                                 @Param("cancelled") PaintJobStatus cancelled,
                                 @Param("reason") String reason);
}
