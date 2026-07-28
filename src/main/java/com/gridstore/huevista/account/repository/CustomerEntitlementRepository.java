package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.CustomerEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerEntitlementRepository extends JpaRepository<CustomerEntitlement, String> {

    Optional<CustomerEntitlement> findByCustomerId(String customerId);

    List<CustomerEntitlement> findByRetailerOrgIdOrderByUpdatedAtDesc(String retailerOrgId);

    /**
     * Every customer a shop is responsible for: the ones it currently manages, PLUS
     * anyone still holding a code it issued.
     *
     * The second half exists because {@code retailerOrg} is a single pointer that moves
     * to whichever shop most recently onboarded the customer. Shop A onboards someone and
     * pays for their projects; the customer later redeems shop B's code; and A's customer
     * simply vanished from A's portal — while A's paid-for allowance was still sitting on
     * the row, and A could no longer grant them anything ("not managed by your
     * organization"). Both shops have a real relationship with that customer, so both
     * should see them.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT DISTINCT e FROM CustomerEntitlement e
             WHERE e.retailerOrg.id = :orgId
                OR EXISTS (SELECT 1 FROM CustomerAccessCode c
                            WHERE c.usedByUser.id = e.customer.id
                              AND c.organization.id = :orgId)
             ORDER BY e.updatedAt DESC
            """)
    List<CustomerEntitlement> findManagedByOrCodedFrom(
            @org.springframework.data.repository.query.Param("orgId") String orgId);

    /** Whether this shop may act on this customer — it manages them, or issued their code. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(e) > 0 FROM CustomerEntitlement e
             WHERE e.customer.id = :customerId
               AND (e.retailerOrg.id = :orgId
                    OR EXISTS (SELECT 1 FROM CustomerAccessCode c
                                WHERE c.usedByUser.id = :customerId
                                  AND c.organization.id = :orgId))
            """)
    boolean isManagedBy(@org.springframework.data.repository.query.Param("customerId") String customerId,
                        @org.springframework.data.repository.query.Param("orgId") String orgId);

    /**
     * Atomically claim one project slot: a single conditional UPDATE, so two concurrent
     * "create project" calls can never both pass on the last remaining slot. The old
     * shape — check the allowance, then increment in a separate call — let a customer
     * with one project left fire parallel requests and get several. Returns 1 when a slot
     * was taken, 0 when the allowance is spent.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            UPDATE CustomerEntitlement e SET e.projectsCreated = e.projectsCreated + 1
             WHERE e.customer.id = :customerId AND e.projectsCreated < e.projectAllowance
               AND e.accessExpiresAt > :now
            """)
    int claimProjectSlot(@org.springframework.data.repository.query.Param("customerId") String customerId,
                         @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}
