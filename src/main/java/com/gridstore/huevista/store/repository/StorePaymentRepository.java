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

    /**
     * Everything the shop has ever earned through its kiosk, in paise. Reversed payments
     * (refunded or charged back) are excluded — that money went back to the customer, so
     * it must not remain redeemable.
     */
    @Query("""
            SELECT COALESCE(SUM(p.retailerSharePaise), 0) FROM StorePayment p
             WHERE p.organization.id = :orgId AND p.reversedAt IS NULL
            """)
    long sumRetailerShareByOrganizationId(@Param("orgId") String orgId);

    /**
     * Row-locked load for applying a refund, so a refund webhook racing a redemption
     * request can't interleave and let an already-reversed share be paid out.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM StorePayment p WHERE p.paymentId = :paymentId")
    Optional<StorePayment> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);
}
