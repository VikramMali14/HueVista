package com.gridstore.huevista.project.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "regions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    private String label; // e.g. "Main Wall", "Accent Wall", "Trim" — display name

    @Enumerated(EnumType.STRING)
    private RegionCategory category;

    // Raw SAM 2 mask output stored as JSON (polygon or RLE)
    @Column(columnDefinition = "TEXT")
    private String maskData;

    // URL of the mask PNG. Long enough to hold a presigned S3 URL — those run
    // ~500 chars with X-Amz signature/expiry/credential params and overflow
    // the default VARCHAR(255). Bumped to 2048 for headroom.
    @Column(length = 2048)
    private String maskUrl;

    /**
     * The mask as it stood before the user's first hand-edit — for a detected wall,
     * wall detection's own output.
     *
     * Filed by the FIRST call to replaceRegionMask and never touched again, so it does
     * not drift into meaning "the previous mask": the tenth refinement still restores
     * the same starting point as the first. The file it names is deliberately kept
     * rather than cleaned up with the mask it replaced, which is what makes "Restore
     * original" survive a reload instead of living in the browser tab that made the edit.
     *
     * Null for every region nobody has edited: there the live mask IS the original, and
     * a restore would be a no-op. The studio reads it exactly that way and only offers
     * the button when there is somewhere to go back to.
     */
    @Column(length = 2048)
    private String originalMaskUrl;

    // Color currently applied to this region (updated via auto-save)
    private String appliedShadeCode;
    private String appliedHexCode;

    private Integer displayOrder;

    // True for walls the user drew by hand in the browser (createCustomMaskRegion),
    // as opposed to AI-detected surfaces. Tracked independently of category so a
    // hand-drawn "Main wall" is still recognised as user-made (and deletable)
    // after a reload. Defaults to false for existing/auto rows.
    @Column(nullable = false)
    private boolean manual;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
