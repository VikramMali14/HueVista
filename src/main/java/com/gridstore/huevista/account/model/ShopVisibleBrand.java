package com.gridstore.huevista.account.model;

import com.gridstore.huevista.paint.model.Brand;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One paint company a shop has chosen to SHOW.
 *
 * The counterpart to {@link RetailerBrandAssignment}, and deliberately not the same
 * table. That one is the distributor's grant — what the shop is permitted to carry,
 * decided above it. This one is the shop's own storefront decision: of the companies
 * it may carry, which it actually stocks. A shop granted six companies that stocks two
 * had no way to say so, and showed all six everywhere.
 *
 * The effective catalogue is the intersection of the two — see
 * {@link com.gridstore.huevista.account.service.BrandAccessService}. A shop can only
 * ever narrow its grant here, never widen it: selecting a company the distributor has
 * not assigned changes nothing, and one revoked later stops appearing whatever this
 * table still says.
 *
 * Whether the selection is a limit at all is stated on the organization
 * ({@code visibleBrandsRestricted}) rather than inferred from row count, for the same
 * reason as every other restriction flag here: no rows can mean "not set up" or "the
 * shop turned everything off", and guessing gets one of them backwards.
 */
@Entity
@Table(name = "shop_visible_brands",
        uniqueConstraints = @UniqueConstraint(columnNames = {"retailer_id", "brand_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopVisibleBrand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The shop that made this choice. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @CreationTimestamp
    private LocalDateTime selectedAt;
}
