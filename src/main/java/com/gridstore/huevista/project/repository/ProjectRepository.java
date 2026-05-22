package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.image.model.ImageType;
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

    /**
     * Pulls the owning user's id without triggering lazy initialization on the
     * Project.user association — needed inside the async segmentation worker,
     * which runs outside any transaction.
     */
    @Query("SELECT p.user.id FROM Project p WHERE p.id = :projectId")
    Optional<String> findUserIdById(@Param("projectId") String projectId);

    /**
     * Reads the upload's image type (INDOOR / OUTDOOR) classified at upload
     * time. Lets the segmentation worker branch prompts and thresholds without
     * pulling the full Project + UploadedImage graph through a lazy proxy.
     */
    @Query("SELECT p.image.imageType FROM Project p WHERE p.id = :projectId")
    Optional<ImageType> findImageTypeById(@Param("projectId") String projectId);
}
