package com.gridstore.huevista.account.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_access_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerAccessCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(unique = true, nullable = false, length = 8)
    private String code;

    @Column(nullable = false)
    private int validDays; // 3, 7, or 14

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_user_id")
    private User usedByUser;

    private LocalDateTime usedAt;

    // True when the code was redeemed by an anonymous guest (no account). usedByUser
    // stays null in that case; usedAt is still set. Lets the shop tell guest
    // redemptions apart, and keeps isUsed() correct for single-use enforcement.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean guestRedeemed = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /** Single-use: consumed once a real user redeems it OR a guest redeems it (usedAt set). */
    public boolean isUsed() {
        return usedByUser != null || usedAt != null;
    }
}
