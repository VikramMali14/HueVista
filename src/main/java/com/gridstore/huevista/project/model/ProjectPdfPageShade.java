package com.gridstore.huevista.project.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One surface of one colour-board page: which region, painted which shade.
 *
 * Denormalised on purpose, the same way {@code RetailerCombo} stores its three slots.
 * A board is a record of what the customer was handed, so it has to keep saying that
 * even after the shade catalogue is re-imported, the shade is retired, or the region is
 * redrawn. The only live reference kept is {@link #regionId} — and it is a plain id
 * rather than a foreign key for the same reason: a page must survive its region being
 * deleted, and it does, because {@link #hexCode} alone is enough to re-render it.
 */
@Entity
@Table(name = "project_pdf_page_shades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectPdfPageShade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "page_id", nullable = false)
    private ProjectPdfPage page;

    /** The region this colour was on, when it still exists. Null once it doesn't. */
    private Long regionId;

    /** What the studio called that surface — "Main wall", "Trim". */
    @Column(length = 255)
    private String regionLabel;

    /** The catalogue shade, as it read on the day. Null for a colour picked freehand. */
    @Column(length = 64)
    private String shadeCode;

    @Column(length = 160)
    private String shadeName;

    /** The colour itself. The one field the render pipeline cannot do without. */
    @Column(nullable = false, length = 16)
    private String hexCode;

    @Column(nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
