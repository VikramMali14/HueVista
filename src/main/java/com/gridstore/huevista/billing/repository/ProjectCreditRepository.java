package com.gridstore.huevista.billing.repository;

import com.gridstore.huevista.billing.model.ProjectCredit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProjectCreditRepository extends JpaRepository<ProjectCredit, String> {

    /** Unspent credits, oldest first — the one a new project should consume. */
    @Query("""
            SELECT c FROM ProjectCredit c
             WHERE c.userId = :userId AND c.consumedAt IS NULL
             ORDER BY c.createdAt ASC
            """)
    List<ProjectCredit> findAvailable(@Param("userId") String userId);

    @Query("SELECT COUNT(c) FROM ProjectCredit c WHERE c.userId = :userId AND c.consumedAt IS NULL")
    int countAvailable(@Param("userId") String userId);

    /**
     * Spend one specific credit. The {@code consumedAt IS NULL} guard makes this a
     * compare-and-set, so two parallel project creations can never both claim the same
     * credit — the loser matches 0 rows and moves on to the next one (or is refused).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProjectCredit c SET c.consumedAt = :now, c.projectId = :projectId
             WHERE c.id = :id AND c.consumedAt IS NULL
            """)
    int consume(@Param("id") String id,
                @Param("projectId") String projectId,
                @Param("now") LocalDateTime now);

    /** Hand a credit back when the project it was spent on could not be created. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProjectCredit c SET c.consumedAt = null, c.projectId = null WHERE c.id = :id")
    int release(@Param("id") String id);

    Optional<ProjectCredit> findFirstByProjectId(String projectId);
}
