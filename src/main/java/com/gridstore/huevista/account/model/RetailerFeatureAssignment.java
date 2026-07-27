package com.gridstore.huevista.account.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One page a distributor has switched on for one of its retailer shops.
 *
 * The sibling of {@link RetailerBrandAssignment}: that one answers "which paint
 * companies may this shop work with?", this one answers "which parts of the product
 * may this shop open?". Both are granted BY a distributor, so both record it — a
 * shop leaving its distributor takes neither with it (see
 * {@code AccountService.unlinkRetailer}).
 *
 * <p>Whether a shop is limited at all is the {@code featuresRestricted} flag on
 * {@link Organization}, not the row count, for exactly the reason spelled out there:
 * "no rows" is ambiguous between "not configured yet" and "every page revoked", and
 * guessing turns the last revoke into a full grant.
 */
@Entity
@Table(name = "retailer_feature_assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"retailer_id", "feature"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetailerFeatureAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The distributor org that granted this page. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Organization distributor;

    /** The retailer (shop) org the page is granted to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature", nullable = false, length = 64)
    private AppFeature feature;

    @CreationTimestamp
    private LocalDateTime assignedAt;
}
