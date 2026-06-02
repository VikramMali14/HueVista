package com.gridstore.huevista.paint.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A paint product line / series (e.g. Asian Paints "Apex Ultima", Baxter
 * "Tuffshield"), scoped to a brand + interior/exterior. This is the shared
 * reference catalogue that drives the cascading checkboxes; shops then create
 * ShopProduct listings against a line with their own price/image/details.
 */
@Entity
@Table(name = "paint_lines",
        uniqueConstraints = @UniqueConstraint(columnNames = {"brand_id", "category", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaintLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductCategory category;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private QualityTier qualityTier = QualityTier.PREMIUM;

    /** Typical finish/sheen for the line (e.g. "Matt", "Sheen"). */
    private String defaultFinish;
}
