package com.gridstore.huevista.project.model;

import com.gridstore.huevista.account.model.CustomerAccessCode;
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

    // Owner is EITHER a registered user OR — for an anonymous guest who redeemed a
    // shop access code — the access code (user stays null). Exactly one is set.
    // On guest sign-up the project is re-pointed to the new user (the shop keeps
    // visibility through the accessCode link, which is never cleared).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_code_id")
    private CustomerAccessCode accessCode;

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

    // Storage key of the model's raw colour-coded mask (RED/GREEN/BLUE/BLACK
    // image) from the generation that was accepted. Kept purely for
    // diagnostics — the admin mask viewer compares it against the processed
    // per-region masks. Null for projects segmented before this shipped and
    // for projects whose regions are manual-only.
    private String rawMaskStorageKey;

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

    // Paint companies the sharing retailer opened up for the share-link viewer's
    // repaint palette, stored as a comma-separated list of brand names. Null or
    // blank means no restriction — the viewer may repaint with every brand.
    @Column(columnDefinition = "TEXT")
    private String shareBrands;

    /** Allowed share-repaint brand names as a list. Empty list = no restriction. */
    public List<String> getShareBrandList() {
        if (shareBrands == null || shareBrands.isBlank()) return List.of();
        return java.util.Arrays.stream(shareBrands.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setShareBrandList(List<String> brands) {
        if (brands == null || brands.isEmpty()) {
            this.shareBrands = null;
            return;
        }
        this.shareBrands = brands.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining(","));
    }

    // When the customer explicitly sent this project to the issuing shop
    // ("I'm done — this is the one"). Null until they do; the portal shows a
    // "sent by customer" badge and the shop owner gets a heads-up email.
    private LocalDateTime sentToShopAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<Region> regions = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
