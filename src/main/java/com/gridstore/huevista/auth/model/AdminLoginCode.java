package com.gridstore.huevista.auth.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A one-time 6-digit second-factor code emailed to an ADMIN on login. One phished
 * admin password can otherwise create retailers, wipe the catalog and read every
 * lead — so admin logins get an email step-up whenever mail delivery is actually
 * configured. Same hardening as {@link PasswordResetCode}: BCrypt-hashed,
 * single-use, time-limited and attempt-limited.
 */
@Entity
@Table(name = "admin_login_codes", indexes = {
        @Index(name = "idx_admin_login_user", columnList = "userId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminLoginCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

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

    @CreationTimestamp
    private LocalDateTime createdAt;
}
