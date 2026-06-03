package com.gridstore.huevista.auth.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A one-time 6-digit verification code sent to a user's email or phone. The code
 * itself is never stored in clear — only a BCrypt hash — and rows are single-use
 * (consumed on success/expiry) with an attempt counter to throttle brute force.
 */
@Entity
@Table(name = "verification_codes", indexes = {
        @Index(name = "idx_vcode_user_channel", columnList = "userId,channel")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationChannel channel;

    /** BCrypt hash of the 6-digit code. */
    @Column(nullable = false)
    private String codeHash;

    /** The email/phone the code was sent to (for audit + masked display). */
    @Column(nullable = false)
    private String destination;

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
