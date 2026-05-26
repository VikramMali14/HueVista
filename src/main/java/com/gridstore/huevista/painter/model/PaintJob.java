package com.gridstore.huevista.painter.model;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.project.model.Project;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A unit of work a retailer routes to a painter. Binds:
 *   - the {@link Project} (room photograph + chosen shades),
 *   - the retailer {@link Organization} owning the commercial relationship,
 *   - the customer {@link User} who originated the project,
 *   - the painter {@link User} executing the job.
 */
@Entity
@Table(name = "paint_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaintJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "painter_id")
    private User painter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaintJobStatus status = PaintJobStatus.NEW;

    /** Retailer-supplied site address — may be richer than the customer's profile. */
    @Column(columnDefinition = "TEXT")
    private String siteAddress;

    /** Sq. ft. estimated from masked regions × camera-derived scale (set on create). */
    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedAreaSqft;

    /** Paint required (litres). Heuristic: areaSqft / coverage(~120 sq.ft/L per coat) × coats. */
    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedPaintLiters;

    /** Painter's quoted price for the job, set on ACCEPTED. */
    @Column(precision = 10, scale = 2)
    private BigDecimal quotedAmountInr;

    /** Days the painter expects the job to take, set on ACCEPTED. */
    private Integer estimatedDays;

    private LocalDateTime scheduledFor;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Captured when status transitions to DECLINED or CANCELLED, surfaced to the retailer. */
    @Column(columnDefinition = "TEXT")
    private String declineReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
