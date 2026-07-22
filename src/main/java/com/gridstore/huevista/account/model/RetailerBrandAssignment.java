package com.gridstore.huevista.account.model;

import com.gridstore.huevista.paint.model.Brand;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One paint brand a distributor has granted to one of its retailer shops.
 *
 * A distributor carries a set of paint companies; here it decides which of them
 * a given shop may work with. A retailer with no assignment rows is treated as
 * "all brands" by the service layer, so shops created before a distributor made
 * a selection keep their unrestricted behaviour.
 */
@Entity
@Table(name = "retailer_brand_assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"retailer_id", "brand_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetailerBrandAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The distributor org that granted this brand. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Organization distributor;

    /** The retailer (shop) org the brand is granted to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @CreationTimestamp
    private LocalDateTime assignedAt;
}
