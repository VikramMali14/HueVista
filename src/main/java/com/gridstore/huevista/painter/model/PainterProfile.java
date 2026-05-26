package com.gridstore.huevista.painter.model;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.paint.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One-to-one extension of a User with role=PAINTER carrying trade-specific
 * fields. Created when the painter redeems an invitation for the first time;
 * editable from the painter's own profile page thereafter.
 */
@Entity
@Table(name = "painter_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PainterProfile {

    @Id
    @Column(name = "user_id")
    private String userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    private String phone;

    /** Service area cities — e.g. ["Belgavi", "Hubballi"]. */
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private List<String> serviceAreas = new ArrayList<>();

    /** Specialties — e.g. ["interior", "exterior", "decorative", "texture", "waterproofing"]. */
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private List<String> specialties = new ArrayList<>();

    private Integer yearsExperience;

    /** Day rate the painter quotes by default (per labourer). Optional. */
    @Column(precision = 10, scale = 2)
    private BigDecimal dayRateInr;

    /** Average customer rating (1.00–5.00), recomputed when reviews land. */
    @Column(precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal rating = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Integer jobsCompleted = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
