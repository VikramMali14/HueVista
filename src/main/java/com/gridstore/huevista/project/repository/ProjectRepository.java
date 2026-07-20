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

    /**
     * Fetch-joins the image because the project list response needs every
     * project's storage key — without the JOIN FETCH that's one lazy-load
     * SELECT per project (N+1). Pageable bounds the result size.
     */
    @Query("SELECT p FROM Project p JOIN FETCH p.image WHERE p.user.id = :userId ORDER BY p.updatedAt DESC")
    List<Project> findByUserIdWithImage(@Param("userId") String userId,
                                        org.springframework.data.domain.Pageable pageable);

    Optional<Project> findByIdAndUserId(String id, String userId);

    Optional<Project> findByShareToken(String shareToken);

    Optional<Project> findByReplicatePredictionId(String predictionId);

    @Query("SELECT p FROM Project p WHERE p.user.id = :userId AND p.status = :status ORDER BY p.updatedAt DESC")
    List<Project> findByUserIdAndStatus(@Param("userId") String userId, @Param("status") ProjectStatus status);

    long countByStatus(ProjectStatus status);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.user.id = :userId")
    long countByUserId(@Param("userId") String userId);

    // --- Guest (anonymous, access-code-scoped) ownership ---
    List<Project> findByAccessCodeIdOrderByUpdatedAtDesc(String accessCodeId);

    Optional<Project> findByIdAndAccessCodeId(String id, String accessCodeId);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.accessCode.id = :accessCodeId")
    long countByAccessCodeId(@Param("accessCodeId") String accessCodeId);

    /**
     * Pulls the owning user's id without triggering lazy initialization on the
     * Project.user association — needed inside the async segmentation worker,
     * which runs outside any transaction.
     */
    @Query("SELECT p.user.id FROM Project p WHERE p.id = :projectId")
    Optional<String> findUserIdById(@Param("projectId") String projectId);

    /**
     * Pulls the owning access code's id (for guest projects, which have no user)
     * without lazy-loading the association — used by the async segmentation worker
     * to derive the storage scope when the project belongs to a guest.
     */
    @Query("SELECT p.accessCode.id FROM Project p WHERE p.id = :projectId")
    Optional<String> findAccessCodeIdById(@Param("projectId") String projectId);

    /**
     * Reads the upload's image type (INDOOR / OUTDOOR) classified at upload
     * time. Lets the segmentation worker branch prompts and thresholds without
     * pulling the full Project + UploadedImage graph through a lazy proxy.
     */
    @Query("SELECT p.image.imageType FROM Project p WHERE p.id = :projectId")
    Optional<ImageType> findImageTypeById(@Param("projectId") String projectId);

    /**
     * Reads the upload's stored image id so the async segmenter can hydrate
     * the full UploadedImage (storage key + cached dimensions) without
     * lazy-loading through Project.user/image. Single-column projection.
     */
    @Query("SELECT p.image.id FROM Project p WHERE p.id = :projectId")
    Optional<String> findImageIdById(@Param("projectId") String projectId);

    /**
     * Reads the ADMIN skip-image-clean testing flag without pulling the full
     * entity — checked by the async segmentation worker before the cleaner
     * step. Empty optional = flag never set = default behaviour.
     */
    @Query("SELECT p.skipImageClean FROM Project p WHERE p.id = :projectId")
    Optional<Boolean> findSkipImageCleanById(@Param("projectId") String projectId);

    /**
     * Reads the project's mask mode ("AUTO"/"MANUAL") without pulling the full
     * entity — checked by the async segmentation worker after the clean-up step.
     * Empty optional / null = default AUTO behaviour.
     */
    @Query("SELECT p.maskMode FROM Project p WHERE p.id = :projectId")
    Optional<String> findMaskModeById(@Param("projectId") String projectId);
}
