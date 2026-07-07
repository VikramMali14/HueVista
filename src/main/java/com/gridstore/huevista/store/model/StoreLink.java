package com.gridstore.huevista.store.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A retailer's public in-store kiosk link ("order at the counter"). Anyone who
 * opens /store/{slug} can pay {@code pricePaise} for one image upload; the
 * platform keeps the configured base amount and the excess accrues to the
 * retailer's wallet. The price is set by the retailer at creation (and can be
 * changed later) but never below the platform base.
 */
@Entity
@Table(name = "store_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** URL token the shop prints/shares: huevista.com/store/{slug}. */
    @Column(unique = true, nullable = false, length = 80)
    private String slug;

    /** What one image upload costs the walk-in customer, in paise (>= platform base). */
    @Column(nullable = false)
    private int pricePaise;

    /** How long each purchased access code (and the guest session it opens) lasts. */
    @Column(nullable = false)
    private int validDays;

    /** The shop can pause the kiosk without losing the printed URL. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
