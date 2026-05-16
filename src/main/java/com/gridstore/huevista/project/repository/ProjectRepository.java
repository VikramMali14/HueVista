package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<Project> findByIdAndUserId(String id, String userId);

    Optional<Project> findByShareToken(String shareToken);

    Optional<Project> findByReplicatePredictionId(String predictionId);

    @Query("SELECT p FROM Project p WHERE p.user.id = :userId AND p.status = :status ORDER BY p.updatedAt DESC")
    List<Project> findByUserIdAndStatus(@Param("userId") String userId, @Param("status") ProjectStatus status);

    long countByStatus(ProjectStatus status);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.user.id = :userId")
    long countByUserId(@Param("userId") String userId);
}
