package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.ProjectGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ProjectGrantRepository extends JpaRepository<ProjectGrant, String> {

    /** This shop's grants, newest first — the list a retailer revokes from. */
    List<ProjectGrant> findByRetailerOrgIdOrderByCreatedAtDesc(String retailerOrgId);

    List<ProjectGrant> findByCustomerUserIdOrderByCreatedAtDesc(String customerUserId);

    List<ProjectGrant> findByAccessCodeIdOrderByCreatedAtDesc(String accessCodeId);

    /**
     * Stamp a grant revoked, exactly once. The {@code revokedAt IS NULL} guard makes it a
     * compare-and-set, so two clicks on the same row can never release the reserved images
     * twice — which would hand the shop back quota it never spent.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectGrant g SET g.revokedAt = :now, g.revokedByUserId = :userId
             WHERE g.id = :id AND g.revokedAt IS NULL
            """)
    int revokeIfLive(@Param("id") String id,
                     @Param("userId") String userId,
                     @Param("now") LocalDateTime now);
}
