package com.gridstore.huevista.library.model;

import com.gridstore.huevista.project.model.RegionCategory;
import jakarta.persistence.*;
import lombok.*;

/**
 * One paintable wall of a template, holding the storage key of a mask PNG that
 * was generated once when the template was published and is never regenerated.
 *
 * Deliberately close in shape to {@link com.gridstore.huevista.project.model.Region}
 * so starting a free project is a field-for-field copy with no processing in
 * between. The one difference is the name: this stores a KEY and says so, where
 * Region calls the same thing {@code maskUrl} for historical reasons.
 */
@Entity
@Table(name = "free_project_template_regions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreeProjectTemplateRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private FreeProjectTemplate template;

    private String label;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RegionCategory category;

    @Column(name = "mask_storage_key", nullable = false, length = 512)
    private String maskStorageKey;

    /** Colour the template opens with, so a free project looks finished on load. */
    @Column(length = 32)
    private String appliedHexCode;

    @Column(length = 64)
    private String appliedShadeCode;

    @Column(nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
