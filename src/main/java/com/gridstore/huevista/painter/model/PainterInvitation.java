package com.gridstore.huevista.painter.model;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One-shot 8-character invitation code a retailer generates so a painter can
 * link to their organization. Mirrors {@code account.CustomerAccessCode} but
 * grants painter role + creates a {@link PainterRetailerLink} on redeem.
 */
@Entity
@Table(name = "painter_invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PainterInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false, length = 16)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    /** Optional — when set, only the painter with this phone number can redeem. */
    private String phoneHint;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime usedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_painter_id")
    private User usedByPainter;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }
}
