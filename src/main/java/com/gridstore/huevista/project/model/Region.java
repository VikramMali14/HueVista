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

    private String label; // e.g. "Wall", "Ceiling", "Door" — from SAM 2 or auto-indexed

    // Raw SAM 2 mask output stored as JSON (polygon or RLE)
    @Column(columnDefinition = "TEXT")
    private String maskData;

    // Optional URL if mask stored as a PNG in S3
    private String maskUrl;

    // Color currently applied to this region (updated via auto-save)
    private String appliedShadeCode;
    private String appliedHexCode;

    private Integer displayOrder;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
