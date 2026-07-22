package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.RetailerBrandAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RetailerBrandAssignmentRepository extends JpaRepository<RetailerBrandAssignment, Long> {

    List<RetailerBrandAssignment> findByRetailerId(String retailerId);

    /** Report view: pull the brand eagerly so a batch of shops resolves without N+1. */
    @Query("select a from RetailerBrandAssignment a join fetch a.brand where a.retailer.id in :retailerIds")
    List<RetailerBrandAssignment> findWithBrandByRetailerIdIn(@Param("retailerIds") List<String> retailerIds);

    @Transactional
    void deleteByRetailerId(String retailerId);
}
