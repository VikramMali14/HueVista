package com.gridstore.huevista.paint.model;

import com.gridstore.huevista.paint.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "shades",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"brand_id", "shade_code"})
        },
        // Postgres does not index FK columns automatically; with a 10k+ catalogue the
        // brand-scoped list/filter queries need these. Created by hbm2ddl (ddl-auto=update).
        indexes = {
                @Index(name = "idx_shades_brand_id", columnList = "brand_id"),
                @Index(name = "idx_shades_popularity", columnList = "popularity"),
                @Index(name = "idx_shades_shade_family", columnList = "shade_family")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    // ── From Asian Paints API ──────────────────────────────────────────────

    @Column(nullable = false)
    private String shadeCode; // entityCode: "9436"

    @Column(nullable = false)
    private String name; // entityName: "air breeze"

    @Column(nullable = false, length = 7)
    private String hexCode; // shadeHexCode: "#F3EDE8"

    private String shadeFamily; // "off whites", "blues", etc.

    private String featureTag; // "Recommended", "Colour of the year", etc.

    private Integer popularity; // rank within the brand catalog

    @Column(length = 500)
    private String pageUrl; // canonical product page

    // From filterTitle map in the API response
    private String colorTemperature; // cool / warm / neutral

    private String tonality; // light / medium / dark

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> suitableRooms; // ["all rooms"], ["bedroom", "living room"]

    // ── Calculated from hex at seed time (no AI needed) ───────────────────

    @Column(precision = 5, scale = 2)
    private BigDecimal lrv; // Light Reflectance Value 0–100

    private Integer rgbR;
    private Integer rgbG;
    private Integer rgbB;

    // ── AI-enriched by Claude once at seed time ───────────────────────────

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> styleTags; // ["modern", "minimalist", "Indian traditional"]

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> moodDescriptors; // ["calm", "airy", "peaceful"]

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> finishRecommendations; // ["matte", "eggshell"]

    @Column(columnDefinition = "TEXT")
    private String aiDescription; // one-line natural language description

    @CreationTimestamp
    private LocalDateTime createdAt;
}
