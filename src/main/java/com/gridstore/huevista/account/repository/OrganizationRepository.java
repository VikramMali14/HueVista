package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, String> {

    boolean existsBySlug(String slug);

    Optional<Organization> findBySlug(String slug);

    List<Organization> findByOwnerIdAndType(String ownerId, OrgType type);

    Optional<Organization> findBySubdomainSlug(String subdomainSlug);
}
