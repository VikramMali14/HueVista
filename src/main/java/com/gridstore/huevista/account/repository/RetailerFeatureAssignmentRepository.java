package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.RetailerFeatureAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetailerFeatureAssignmentRepository extends JpaRepository<RetailerFeatureAssignment, Long> {

    List<RetailerFeatureAssignment> findByRetailerId(String retailerId);

    void deleteByRetailerId(String retailerId);
}
