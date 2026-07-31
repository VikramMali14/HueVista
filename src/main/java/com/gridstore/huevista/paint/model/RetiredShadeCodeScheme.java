package com.gridstore.huevista.paint.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A shade-code pattern a shop has stopped using.
 *
 * Codes outlive the scheme that made them. A shop prints its numbering on colour boards,
 * quotes, WhatsApp messages and the customer's own photo of the counter screen — and then
 * changes the pattern. Every one of those codes instantly became unreadable: the checker
 * decoded with the CURRENT pattern only, so a customer walking in with last season's card
 * got "not a valid code" for a code the shop itself had issued.
 *
 * Retiring the old pattern here instead of overwriting it keeps them readable. Nothing is
 * ever ENCODED with a retired pattern — new codes always use the live one — so this is
 * strictly a decode-side record.
 */
@Entity
@Table(name = "retired_shade_code_schemes",
        indexes = @Index(name = "idx_retired_scheme_org", columnList = "organization_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetiredShadeCodeScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 4)
    @Builder.Default
    private String prefix = "";

    @Column(nullable = false, length = 2)
    @Builder.Default
    private String infix = "";

    @Column(nullable = false, length = 4)
    @Builder.Default
    private String suffix = "";

    /** When this pattern went out of use — what the shop reads to date an old card. */
    @CreationTimestamp
    private LocalDateTime retiredAt;
}
