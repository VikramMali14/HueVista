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
}
