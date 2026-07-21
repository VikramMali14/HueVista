package com.gridstore.huevista.paint.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A retailer's product listing: a chosen paint line plus the shop's own price,
 * pack size, coverage, finish, quality/brightness rating, image and details.
 */
@Entity
@Table(name = "shop_products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paint_line_id", nullable = false)
    private PaintLine line;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    /** Unit the price is for, e.g. "20 L", "4 L", "per litre". */
    private String priceUnit;

    private String packSize;

    /** Free text, e.g. "120–140 sq ft/L per coat". */
    private String coverage;

    private String finish;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private QualityTier qualityTier = QualityTier.PREMIUM;

    /** 1–10 brightness/quality score shown in the indicator. */
    @Builder.Default
    private Integer brightness = 8;

    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String features;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
