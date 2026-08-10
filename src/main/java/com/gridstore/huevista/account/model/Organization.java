package com.gridstore.huevista.account.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgType type;

    // Optional location captured at retailer signup.
    private String city;
    private String state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    // Distributor commission rate (e.g. 3.00 = 3%)
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.valueOf(3.00);

    /**
     * RETAILER only: whether this shop's catalogue is limited to the brands its
     * distributor assigned.
     *
     * Kept as an explicit flag because "no assignment rows" is genuinely ambiguous —
     * it can mean "not set up yet" (browse everything) or "the distributor revoked
     * every brand" (browse nothing). Inferring it from row count made revoking a
     * shop's last brand GRANT them the whole catalogue, the exact opposite of intent.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean brandsRestricted = false;

    /**
     * RETAILER only: whether this shop's pages are limited to the ones its
     * distributor switched on (see {@link RetailerFeatureAssignment}).
     *
     * Same explicit-flag reasoning as {@link #brandsRestricted} above — "no
     * assignment rows" cannot distinguish "never configured" from "every page
     * revoked", so the restriction is stated rather than inferred.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean featuresRestricted = false;

    /**
     * RETAILER only: whether the shop has narrowed its catalogue to the companies it
     * chose to show (see {@link ShopVisibleBrand}).
     *
     * The shop's own storefront decision, and a different question from
     * {@link #brandsRestricted} — that one is the distributor's grant, deciding what
     * this shop is PERMITTED to carry. This one decides what it actually puts in front
     * of people, and applies everywhere at once: the counter's own studio, the kiosk,
     * every access code it issues, every customer it onboards. A shop can only narrow
     * its grant here; the two are intersected, so selecting a company the distributor
     * never assigned changes nothing.
     *
     * Same explicit flag as the two above, for the same reason: "no rows" cannot tell
     * "never configured" from "the shop turned every company off".
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean visibleBrandsRestricted = false;

    /**
     * RETAILER only: whether paint NAMES are shown to anyone working under this shop.
     *
     * A shop that has built its own shade-code scheme is usually hiding which paint
     * company a colour comes from; leaving "Asian Paints Ivory Mist" printed beside the
     * coded number gives it straight back, and a customer can search the real product in
     * seconds. Turning this off drops the name everywhere the colour appears — studio,
     * PDF board, share link, kiosk — leaving the shop's own code as the only handle.
     *
     * Lives on the organization rather than the code scheme because the scheme row is
     * deleted when every part is blank, and a shop may well want names hidden without
     * running a code pattern at all.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default true")
    @Builder.Default
    private boolean showShadeNames = true;

    // White-label portal (RETAILER only)
    @Builder.Default
    private boolean whitelabelEnabled = false;

    @Column(unique = true)
    private String subdomainSlug;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
