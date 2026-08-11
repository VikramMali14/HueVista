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

    /**
     * Every room created under a code any of these organizations issued — the shop's
     * side of its customers' work, for the retailer dashboard. The image is fetch-joined
     * for the same N+1 reason as {@link #findByUserIdWithImage}, and the customer's own
     * account is joined lazily-safe through the code.
     *
     * Projects the shop owner created themselves are excluded ({@code p.user.id <> ...}
     * is not enough — a retailer who redeemed their own code would appear twice), so the
     * caller can concatenate this with the owner's own list without de-duplicating.
     * Callers must guard against an empty collection (JPQL {@code IN ()} is invalid).
     */
    @Query("""
            SELECT p FROM Project p
              JOIN FETCH p.image
              JOIN p.accessCode c
             WHERE c.organization.id IN :orgIds
               AND (p.user IS NULL OR p.user.id <> :excludeUserId)
             ORDER BY p.updatedAt DESC
            """)
    List<Project> findByIssuingOrgIds(@Param("orgIds") java.util.Collection<String> orgIds,
                                      @Param("excludeUserId") String excludeUserId,
                                      org.springframework.data.domain.Pageable pageable);

    /**
     * Projects whose paid validity window is parked because the owner was subscribed
     * when it was last looked at. The nightly sweep re-checks each owner and resumes the
     * ones whose plan has since ended.
     */
    @Query("SELECT p FROM Project p WHERE p.accessPausedAt IS NOT NULL AND p.user IS NOT NULL")
    List<Project> findPausedWindows();

    /**
     * Every project on the platform, whoever owns it — the admin mask browser.
     *
     * Ownership is deliberately not a filter here, which is the whole point: a room is
     * owned by a user OR (for a walk-in who redeemed a code) by the code alone, and the
     * admin looking into a reported bad run has to be able to reach both. The owner and
     * the issuing shop are LEFT JOINed for exactly that reason — either side can be
     * absent — and fetch-joined along with the image so listing a page of rooms is one
     * query rather than four per row.
     *
     * {@code q} matches the room name, the owner's name/email, the shop's name, or the
     * code itself, so an admin can start from whatever the report gave them. Blank
     * matches everything.
     */
    @Query("""
            SELECT p FROM Project p
              JOIN FETCH p.image
              LEFT JOIN FETCH p.user u
              LEFT JOIN FETCH p.accessCode c
              LEFT JOIN FETCH c.organization o
             WHERE :q = ''
                OR LOWER(p.name) LIKE :q
                OR LOWER(p.id) LIKE :q
                OR LOWER(COALESCE(u.name, '')) LIKE :q
                OR LOWER(COALESCE(u.email, '')) LIKE :q
                OR LOWER(COALESCE(o.name, '')) LIKE :q
                OR LOWER(COALESCE(c.code, '')) LIKE :q
             ORDER BY p.updatedAt DESC
            """)
    List<Project> searchAll(@Param("q") String lowercasedLikePattern,
                            org.springframework.data.domain.Pageable pageable);

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
     * Rooms under this code the customer has already handed to the shop. Non-zero means
     * the visit is finished, which is where guest re-entry stops: the code is 8 characters
     * on a printed slip, and re-entry hands whoever reads one a session into that
     * customer's room for the code's whole life.
     */
    @Query("""
            SELECT COUNT(p) FROM Project p
             WHERE p.accessCode.id = :accessCodeId AND p.sentToShopAt IS NOT NULL
            """)
    long countSentToShopByAccessCodeId(@Param("accessCodeId") String accessCodeId);

    /**
     * Rooms created per access code as [codeId, count] — one query for a whole
     * page of the shop's codes, so the "projects used" column doesn't cost an
     * extra COUNT per row. Callers must guard against an empty collection
     * (JPQL {@code IN ()} is invalid).
     */
    @Query("""
            SELECT p.accessCode.id, COUNT(p)
              FROM Project p
             WHERE p.accessCode.id IN :accessCodeIds
             GROUP BY p.accessCode.id
            """)
    List<Object[]> countByAccessCodeIds(@Param("accessCodeIds") java.util.Collection<String> accessCodeIds);

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
