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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.CREATED;

    // Replicate prediction ID — used to poll SAM 2 job status
    private String replicatePredictionId;

    // When status == FAILED, why. Surfaced to the frontend so we can show the
    // user something actionable ("auto-segmentation not configured — click each
    // wall") instead of a generic failure.
    @Column(length = 500)
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
