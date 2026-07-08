package com.gridstore.huevista.paint.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A retailer-curated three-shade combination ("shop pick") shown to that shop's
 * customers and guests in the studio's AI Suggest tab. The three slots follow the
 * studio's palette roles in order: main wall, accent wall, trim.
 *
 * Shades are stored denormalised (code + name + hex per slot) rather than as FKs
 * into the shades table: the combo must keep rendering exactly as the retailer
 * saved it even if the catalogue is re-imported or a shade is renamed, and the
 * studio only needs these three fields to draw and apply a swatch.
 */
@Entity
@Table(name = "retailer_combos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetailerCombo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ComboScope scope;

    // Slot 1 — main wall.
    @Column(name = "shade1_code", nullable = false, length = 40)
    private String shade1Code;
    @Column(name = "shade1_name", nullable = false, length = 120)
    private String shade1Name;
    @Column(name = "shade1_hex", nullable = false, length = 7)
    private String shade1Hex;

    // Slot 2 — accent wall.
    @Column(name = "shade2_code", nullable = false, length = 40)
    private String shade2Code;
    @Column(name = "shade2_name", nullable = false, length = 120)
    private String shade2Name;
    @Column(name = "shade2_hex", nullable = false, length = 7)
    private String shade2Hex;

    // Slot 3 — trim.
    @Column(name = "shade3_code", nullable = false, length = 40)
    private String shade3Code;
    @Column(name = "shade3_name", nullable = false, length = 120)
    private String shade3Name;
    @Column(name = "shade3_hex", nullable = false, length = 7)
    private String shade3Hex;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
