package com.gridstore.huevista.auth.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A one-time code texted to a mobile number so its holder can sign in.
 *
 * <p>Keyed to the NUMBER, not to a user — which is what makes it different from
 * {@link VerificationCode} and {@link PasswordResetCode}, both of which belong to
 * somebody already signed in or already known. This is the code that runs before there
 * is an account at all: the number may open an existing account, or become a new one,
 * and which of those it is must not be decided (or revealed) at the point the code is
 * sent.
 *
 * <p>Same hardening as its siblings: BCrypt-hashed at rest so a database read hands over
 * no sign-ins, single-use, time-limited and attempt-limited.
 */
@Entity
@Table(name = "phone_login_codes", indexes = {
        @Index(name = "idx_phone_login_phone", columnList = "phoneNumber")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneLoginCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalized, as {@code PhoneNumbers.normalize} writes it. */
    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean consumed = false;

    /**
     * What the requester should be called if this number turns out to have no account.
     *
     * <p>Carried on the code rather than asked for again at the verify step, because the
     * name is given on the first screen and the second screen has one field on it. Null
     * whenever they did not offer one.
     */
    @Column(length = 100)
    private String signUpName;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
