package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, String> {

    boolean existsBySlug(String slug);

    Optional<Organization> findBySlug(String slug);

    List<Organization> findByOwnerIdAndType(String ownerId, OrgType type);

    Optional<Organization> findBySubdomainSlug(String subdomainSlug);

    /**
     * Row-locked load used to serialize wallet redemption requests per org: the
     * balance check and the PENDING insert must be atomic, or two concurrent
     * requests could each pass the check and overdraw the wallet together.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Organization o WHERE o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") String id);
}
