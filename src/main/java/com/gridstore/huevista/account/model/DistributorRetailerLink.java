package com.gridstore.huevista.account.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "distributor_retailer_links",
        uniqueConstraints = @UniqueConstraint(columnNames = {"distributor_id", "retailer_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DistributorRetailerLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Organization distributor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retailer_id", nullable = false)
    private Organization retailer;

    // Override commission rate from distributor default (nullable = use distributor default)
    @Column(precision = 5, scale = 2)
    private BigDecimal commissionRateOverride;

    @CreationTimestamp
    private LocalDateTime linkedAt;
}
