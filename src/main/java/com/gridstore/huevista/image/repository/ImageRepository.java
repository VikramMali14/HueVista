package com.gridstore.huevista.image.repository;

import com.gridstore.huevista.image.model.UploadedImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<UploadedImage, String> {
    List<UploadedImage> findByUserIdOrderByUploadedAtDesc(String userId,
            org.springframework.data.domain.Pageable pageable);
    Optional<UploadedImage> findByIdAndUserId(String id, String userId);

    // Guest (anonymous, access-code-scoped) ownership.
    Optional<UploadedImage> findByIdAndAccessCodeId(String id, String accessCodeId);
    long countByAccessCodeId(String accessCodeId);

    /** How many image rows name this exact file. */
    long countByStorageKey(String storageKey);

    /**
     * Hand every uploaded photo owned by one account to another, for an account merge.
     * The photos have to travel with the rooms built on them — a room whose image still
     * belonged to a retired account would 404 the moment its owner opened it.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UploadedImage i SET i.user = :target WHERE i.user.id = :fromUserId")
    int reassignOwner(@Param("target") com.gridstore.huevista.auth.model.User target,
                      @Param("fromUserId") String fromUserId);

    /**
     * The same count for many files at once, as {@code [storageKey, count]} pairs.
     *
     * Free-library templates are the reason this exists: every copy someone starts
     * shares the template's stored photo, so counting the rows that name it is how
     * the admin page knows whether deleting those files would blank out rooms that
     * are open. One grouped query rather than one count per template — the gallery
     * asks about every template on the shelf at once. Keys with no rows are simply
     * absent from the result.
     */
    @Query("SELECT i.storageKey, COUNT(i) FROM UploadedImage i WHERE i.storageKey IN :keys GROUP BY i.storageKey")
    List<Object[]> countByStorageKeys(@Param("keys") Collection<String> keys);
}
