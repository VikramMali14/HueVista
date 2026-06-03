package com.gridstore.huevista.project.model;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.image.model.UploadedImage;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private UploadedImage image;

    @Column(nullable = false)
    private String name;

    // Optional context captured on the "new project" details step (nullable).
    private String roomType;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.CREATED;

    // Replicate prediction ID — used to poll SAM 2 job status
    private String replicatePredictionId;

    // Storage key (NOT full URL) of the cleaned image produced by
    // ImageCleanerService when the cleaner is enabled. The frontend
    // resolves a presigned URL via StorageService at read time. Null
    // when cleaning is disabled or hasn't run yet. Both the painted
    // preview canvas AND the mask images are aligned to THIS image
    // when present, falling back to the original UploadedImage when not.
    private String cleanedImageStorageKey;

    // When status == FAILED, why. Surfaced to the frontend so we can show the
    // user something actionable ("auto-segmentation not configured — click each
    // wall") instead of a generic failure. Stored as TEXT because Hibernate
    // exception messages with SQL fragments and presigned URLs blow past any
    // reasonable VARCHAR limit.
    @Column(columnDefinition = "TEXT")
    private String failureReason;

    // Share link — null until generated
    @Column(unique = true)
    private String shareToken;

    private LocalDateTime shareExpiresAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<Region> regions = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
