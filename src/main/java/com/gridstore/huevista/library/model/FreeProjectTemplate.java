package com.gridstore.huevista.library.model;

import com.gridstore.huevista.image.model.ImageType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A ready-made room in the free library: one photo, one mask per wall, published
 * once by an admin and opened by anyone.
 *
 * This entity holds no pixels — only the storage keys of files that live exactly
 * once under {@code free-projects/}. Everything a copy needs (content type, size,
 * dimensions) is denormalised onto the row so cloning a template never has to
 * read the image back out of storage.
 */
@Entity
@Table(name = "free_project_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreeProjectTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Stable handle, and the storage folder — kept to [a-z0-9-]. */
    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TemplateSpace space;

    /** "LIVING_ROOM", "KITCHEN", "HALL" … for interiors; "TRADITIONAL", "MODERN" … for exteriors. */
    @Column(nullable = false, length = 64)
    private String roomKey;

    /** How that key is written out for a human ("Living room"). */
    @Column(nullable = false, length = 80)
    private String roomLabel;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 512)
    private String imageStorageKey;

    @Column(nullable = false, length = 100)
    private String imageContentType;

    @Column(nullable = false)
    @Builder.Default
    private long imageFileSize = 0L;

    private Integer imageWidth;
    private Integer imageHeight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ImageType imageType = ImageType.UNKNOWN;

    /**
     * The AI-cleaned photo, when the source project had one. The masks are aligned
     * to this image rather than the original, so a copy that dropped it would paint
     * the walls in the wrong place.
     */
    @Column(length = 512)
    private String cleanedImageStorageKey;

    /** Unpublished templates are listed for the admin but refused by "start a copy". */
    @Column(nullable = false)
    @Builder.Default
    private boolean published = true;

    @Column(nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /** How many copies have been started. Bumped on start; purely informational. */
    @Column(nullable = false)
    @Builder.Default
    private long timesUsed = 0L;

    /** The project this was frozen from, and who froze it. Both may outlive their targets. */
    private String sourceProjectId;

    private String createdByUserId;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<FreeProjectTemplateRegion> regions = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
