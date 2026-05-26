package com.gridstore.huevista.painter.model;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Many-to-many bridge between a painter (User, role=PAINTER) and a retailer
 * Organization. Created when the painter redeems a PainterInvitation. A single
 * painter may have multiple links — one per retailer they work with.
 */
@Entity
@Table(
        name = "painter_retailer_links",
        uniqueConstraints = @UniqueConstraint(columnNames = {"painter_id", "retailer_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PainterRetailerLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "painter_id", nullable = false)
    private User painter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PainterLinkStatus status = PainterLinkStatus.ACTIVE;

    /** Retailer-specific commission cut (e.g. 5.00 = 5%) — optional override. */
    @Column(precision = 5, scale = 2)
    private BigDecimal commissionPct;

    private LocalDateTime invitedAt;

    private LocalDateTime acceptedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
