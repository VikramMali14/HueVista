package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.RetailerCombo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RetailerComboRepository extends JpaRepository<RetailerCombo, String> {

    List<RetailerCombo> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);

    Optional<RetailerCombo> findByIdAndOrganizationId(String id, String organizationId);

    long countByOrganizationId(String organizationId);
}
