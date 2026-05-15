package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerAccessCodeRepository extends JpaRepository<CustomerAccessCode, String> {

    Optional<CustomerAccessCode> findByCode(String code);

    List<CustomerAccessCode> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);

    boolean existsByCode(String code);
}
