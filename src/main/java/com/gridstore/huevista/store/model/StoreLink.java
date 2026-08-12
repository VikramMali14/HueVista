package com.gridstore.huevista.store.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A retailer's public in-store kiosk link ("order at the counter"). Anyone who
 * opens /store/{slug} pays the flat platform kiosk price for one image upload.
 *
 * The shop chooses the slug and the code validity, NOT the price: the walk-in is
 * HueVista's own customer and the whole payment is HueVista's, with the shop rewarded in
 * closed-loop points instead of a share. The price therefore lives in configuration
 * ({@code app.store.price-paise}), not on this row — a printed link keeps working when
 * the platform price changes, and there is no per-shop price to reconcile.
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

    /** URL token the shop prints/shares: app.huevista.org/store/{slug}. */
    @Column(unique = true, nullable = false, length = 80)
    private String slug;

    /**
     * How long each purchased access code (and the guest session it opens) lasts.
     *
     * No longer chosen per link — the shop was picking 3, 7 or 14 days at creation
     * while its counter-issued codes ran a fixed 10, which read as one of the two
     * being wrong. New links take the platform default; the column stays because
     * links created under the old form carry the number the shop picked, and a code
     * already sold under one must keep the window it was sold with.
     */
    @Column(nullable = false)
    private int validDays;

    /** The shop can pause the kiosk without losing the printed URL. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * When the shop deleted this link, or null while it is live.
     *
     * Soft, because {@code store_payments.store_link_id} is NOT NULL: a hard delete
     * would take the shop's own sales history and the points audit behind it with
     * the link. A deleted link stops serving its slug immediately and leaves the
     * shop's list, and the walk-ins who already bought through it keep the codes
     * they paid for.
     */
    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** True once the shop has deleted it — no longer served, no longer listed. */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
