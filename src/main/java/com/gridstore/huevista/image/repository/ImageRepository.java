package com.gridstore.huevista.image.repository;

import com.gridstore.huevista.image.model.UploadedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<UploadedImage, String> {
    List<UploadedImage> findByUserIdOrderByUploadedAtDesc(String userId);
    Optional<UploadedImage> findByIdAndUserId(String id, String userId);

    // Guest (anonymous, access-code-scoped) ownership.
    Optional<UploadedImage> findByIdAndAccessCodeId(String id, String accessCodeId);
}
