package com.gridstore.huevista.paint.model;

import com.gridstore.huevista.account.model.Organization;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A shop's shade-code scheme: ONE pattern instead of a custom code per shade.
 *
 * The customer-facing code is derived, never stored:
 * {@code PREFIX + code[0..2] + INFIX + code[2..] + SUFFIX} — e.g. shade
 * {@code L124} with prefix {@code AB}, infix {@code XY}, suffix {@code CD}
 * reads {@code ABL1XY24CD}. All three parts are optional (kept as empty
 * strings, never null); an all-empty scheme is deleted rather than kept.
 */
@Entity
@Table(name = "shade_code_schemes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadeCodeScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Up to 4 characters placed before the shade code. */
    @Column(nullable = false, length = 4)
    @Builder.Default
    private String prefix = "";

    /** Up to 2 characters inserted after the first two characters of the shade code. */
    @Column(nullable = false, length = 2)
    @Builder.Default
    private String infix = "";

    /** Up to 4 characters placed after the shade code. */
    @Column(nullable = false, length = 4)
    @Builder.Default
    private String suffix = "";

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
