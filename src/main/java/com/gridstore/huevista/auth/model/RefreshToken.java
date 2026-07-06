package com.gridstore.huevista.auth.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * SHA-256 hex of the raw token (see {@code TokenHasher}). The raw value is
     * returned to the client once and never persisted, so a DB leak can't be
     * replayed as live sessions.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant expiryDate;

    /**
     * When this token was consumed by rotation; null while still live. Consumed
     * rows are kept briefly (instead of deleted) so parallel requests that raced
     * the same refresh can be honoured within a short grace window rather than
     * logging the user out — and so reuse long after rotation can be detected
     * as theft. Purged by the nightly cleanup job.
     */
    @Column
    private Instant usedAt;
}
