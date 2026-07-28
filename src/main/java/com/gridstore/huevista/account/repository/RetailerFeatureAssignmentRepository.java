package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.RetailerFeatureAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetailerFeatureAssignmentRepository extends JpaRepository<RetailerFeatureAssignment, Long> {

    List<RetailerFeatureAssignment> findByRetailerId(String retailerId);

    /**
     * Page grants for a whole set of shops in one query — the network report's need.
     * That report used to loop {@link #findByRetailerId} once per shop while its own
     * comment claimed it stayed at two queries regardless of network size, so an admin
     * viewing the platform paid a query per retailer.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT a FROM RetailerFeatureAssignment a
             WHERE a.retailer.id IN :retailerIds
            """)
    List<RetailerFeatureAssignment> findByRetailerIdIn(
            @org.springframework.data.repository.query.Param("retailerIds")
            java.util.Collection<String> retailerIds);

    void deleteByRetailerId(String retailerId);
}
