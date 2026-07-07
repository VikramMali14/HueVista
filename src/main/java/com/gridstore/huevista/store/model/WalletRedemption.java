package com.gridstore.huevista.store.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A retailer's request to cash out wallet earnings to their UPI id. Payouts are
 * manual: creating the request emails the redemption inbox, an admin approves or
 * rejects, and an approval means the admin actually sent the money over UPI.
 * User references are plain id strings (not FKs) so account deletion never
 * blocks on payout history.
 */
@Entity
@Table(name = "wallet_redemptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private int amountPaise;

    /** Where the admin sends the money, e.g. shopname@okhdfcbank. */
    @Column(name = "upi_id", nullable = false)
    private String upiId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WalletRedemptionStatus status = WalletRedemptionStatus.PENDING;

    @Column(name = "requested_by_user_id", nullable = false)
    private String requestedByUserId;

    @Column(name = "decided_by_user_id")
    private String decidedByUserId;

    private LocalDateTime decidedAt;

    @Column(length = 1000)
    private String adminNote;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
