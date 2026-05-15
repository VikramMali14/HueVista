package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.OrgMembership;
import com.gridstore.huevista.account.model.OrgMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, Long> {

    List<OrgMembership> findByOrganizationId(String organizationId);

    List<OrgMembership> findByUserId(String userId);

    Optional<OrgMembership> findByUserIdAndOrganizationId(String userId, String organizationId);

    boolean existsByUserIdAndOrganizationIdAndRole(String userId, String organizationId, OrgMemberRole role);

    void deleteByUserIdAndOrganizationId(String userId, String organizationId);
}
