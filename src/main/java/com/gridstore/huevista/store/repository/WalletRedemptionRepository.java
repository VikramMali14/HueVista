package com.gridstore.huevista.store.repository;

import com.gridstore.huevista.store.model.WalletRedemption;
import com.gridstore.huevista.store.model.WalletRedemptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WalletRedemptionRepository extends JpaRepository<WalletRedemption, String> {

    List<WalletRedemption> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);

    /** Admin queue — organization join-fetched so listing doesn't N+1 on org names. */
    @Query("SELECT r FROM WalletRedemption r JOIN FETCH r.organization ORDER BY r.createdAt DESC")
    List<WalletRedemption> findAllWithOrganization();

    @Query("""
            SELECT r FROM WalletRedemption r JOIN FETCH r.organization
             WHERE r.status = :status ORDER BY r.createdAt DESC
            """)
    List<WalletRedemption> findByStatusWithOrganization(@Param("status") WalletRedemptionStatus status);

    /**
     * Paise already out of (or held from) the balance: everything not REJECTED.
     * PENDING holds funds so a second request can't spend them again; APPROVED
     * means the money was actually paid out.
     */
    @Query("""
            SELECT COALESCE(SUM(r.amountPaise), 0) FROM WalletRedemption r
             WHERE r.organization.id = :orgId AND r.status <> com.gridstore.huevista.store.model.WalletRedemptionStatus.REJECTED
            """)
    long sumHeldByOrganizationId(@Param("orgId") String orgId);

    @Query("""
            SELECT COALESCE(SUM(r.amountPaise), 0) FROM WalletRedemption r
             WHERE r.organization.id = :orgId AND r.status = :status
            """)
    long sumByOrganizationIdAndStatus(@Param("orgId") String orgId, @Param("status") WalletRedemptionStatus status);
}
