package com.gridstore.huevista.store.repository;

import com.gridstore.huevista.store.model.StorePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StorePaymentRepository extends JpaRepository<StorePayment, String> {

    Optional<StorePayment> findByPaymentId(String paymentId);

    List<StorePayment> findTop50ByOrganizationIdOrderByCreatedAtDesc(String organizationId);

    /** Everything the shop has ever earned through its kiosk, in paise. */
    @Query("SELECT COALESCE(SUM(p.retailerSharePaise), 0) FROM StorePayment p WHERE p.organization.id = :orgId")
    long sumRetailerShareByOrganizationId(@Param("orgId") String orgId);
}
