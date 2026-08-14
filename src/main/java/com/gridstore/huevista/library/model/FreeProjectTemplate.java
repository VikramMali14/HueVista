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

    /**
     * Which public page this room appears on once published.
     *
     * Orthogonal to {@link #published}: that says whether the room is on the site
     * at all, this says where. Hiding a room takes it off both pages regardless.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TemplatePlacement placement = TemplatePlacement.DEFAULT;

    // ─── Editorial copy for the "Our work" page ──────────────────────────────
    // All optional, all shown only on /work. A room can be put on that page with
    // none of it filled in — the page then reads what it can off the room itself
    // (its shades, its room type, when it was published) and simply omits the
    // sections it has nothing for. These exist so a portfolio entry can say the
    // things a photograph cannot: where it was, who previewed it, what happened.

    /** "Pune", "Bengaluru" — where the room is. */
    @Column(length = 120)
    private String location;

    /** Free text rather than a number: "2026", "Winter 2025" both read fine. */
    @Column(name = "project_year", length = 16)
    private String projectYear;

    /** The attribution line under the story — "Previewed at the counter · Pune". */
    @Column(length = 200)
    private String credit;

    /** One sentence for the card and the page's lead. */
    @Column(name = "blurb", length = 400)
    private String blurb;

    /** The full story. Paragraphs are separated by blank lines. */
    @Column(columnDefinition = "TEXT")
    private String story;

    /**
     * The stat row under the story: one {@code Label: Value} per line.
     *
     * Free text rather than its own table because it is display copy with no
     * meaning to anything else in the system — nothing queries "photo to preview"
     * — and a table would buy referential integrity over three strings an admin
     * retypes whenever they feel like it.
     */
    @Column(columnDefinition = "TEXT")
    private String stats;

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
