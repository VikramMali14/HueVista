package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.OrgMembership;
import com.gridstore.huevista.account.model.OrgMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, Long> {

    List<OrgMembership> findByOrganizationId(String organizationId);

    /** User ids holding a given role in an org, without lazy-loading each membership's User (avoids N+1). */
    @Query("SELECT m.user.id FROM OrgMembership m WHERE m.organization.id = :orgId AND m.role = :role")
    List<String> findUserIdsByOrganizationIdAndRole(@Param("orgId") String orgId, @Param("role") OrgMemberRole role);

    List<OrgMembership> findByUserId(String userId);

    Optional<OrgMembership> findByUserIdAndOrganizationId(String userId, String organizationId);

    boolean existsByUserIdAndOrganizationIdAndRole(String userId, String organizationId, OrgMemberRole role);

    void deleteByUserIdAndOrganizationId(String userId, String organizationId);
}
