package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.CustomerEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerEntitlementRepository extends JpaRepository<CustomerEntitlement, String> {

    Optional<CustomerEntitlement> findByCustomerId(String customerId);

    List<CustomerEntitlement> findByRetailerOrgIdOrderByUpdatedAtDesc(String retailerOrgId);

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
