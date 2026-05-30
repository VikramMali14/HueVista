package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.CustomerEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerEntitlementRepository extends JpaRepository<CustomerEntitlement, String> {

    Optional<CustomerEntitlement> findByCustomerId(String customerId);

    List<CustomerEntitlement> findByRetailerOrgIdOrderByUpdatedAtDesc(String retailerOrgId);
}
