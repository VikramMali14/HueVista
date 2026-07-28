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
     * Every reward point this shop's kiosk has ever earned, in paise of spending power.
     * Reversed payments (refunded or charged back) are excluded — that money went back to
     * the customer, so the sale must stop counting as earned.
     *
     * Reporting only: the spendable balance is the owner's billing wallet, which is
     * debited directly when the points are clawed back.
     */
    @Query("""
            SELECT COALESCE(SUM(p.bonusPointsPaise), 0) FROM StorePayment p
             WHERE p.organization.id = :orgId AND p.reversedAt IS NULL
            """)
    long sumBonusPointsByOrganizationId(@Param("orgId") String orgId);

    /**
     * Row-locked load for applying a refund, so two refund webhooks for the same payment
     * can't both claw back the points it earned.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM StorePayment p WHERE p.paymentId = :paymentId")
    Optional<StorePayment> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);
}
