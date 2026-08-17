package com.gridstore.huevista.store.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A one-time code e-mailed to a kiosk buyer so they can get back into the account
 * their purchase lives on.
 *
 * <p>This exists so the printed access code does not have to be the way back in. That
 * slip never expires and is handed across a counter — as a credential it is a password
 * lying in a bin. What proves someone is the buyer is reaching the address the buyer
 * gave when they paid, which is what this is.
 *
 * <p>Kept in its own table rather than folded into {@code verification_codes}: those
 * codes confirm an address someone is already signed in to own, while these GRANT a
 * session to someone who is signed in to nothing. Sharing a table would put a code
 * issued for the weaker purpose within reach of the stronger one.
 *
 * <p>Stored as a BCrypt hash, single-use, expiring, with an attempt counter — the same
 * shape as {@code VerificationCode}, for the same reasons.
 */
@Entity
@Table(name = "kiosk_reentry_codes", indexes = {
        @Index(name = "idx_kiosk_reentry_destination", columnList = "destination"),
        @Index(name = "idx_kiosk_reentry_user", columnList = "userId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KioskReentryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The account a correct code opens a session on. */
    @Column(nullable = false)
    private String userId;

    /** BCrypt hash of the 6-digit code. The code itself is never stored. */
    @Column(nullable = false)
    private String codeHash;

    /** The normalized address it was sent to — also how a confirm finds this row. */
    @Column(nullable = false, length = 320)
    private String destination;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int attempts = 0;

    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean consumed = false;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
