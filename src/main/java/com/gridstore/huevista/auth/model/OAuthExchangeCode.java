package com.gridstore.huevista.auth.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A one-time code minted after a successful Google OAuth2 login. The browser is
 * redirected to the frontend callback with THIS short-lived code instead of the
 * real tokens — a URL fragment is invisible to servers but readable by browser
 * extensions and (historically) synced history, and the refresh token lives for
 * days. The callback exchanges the code within seconds via
 * {@code POST /api/auth/oauth2/exchange}; the code is stored SHA-256-hashed,
 * single-use and expires in one minute, so anything scraped from the URL later
 * is worthless.
 */
@Entity
@Table(name = "oauth_exchange_codes", indexes = {
        @Index(name = "idx_oauth_exchange_hash", columnList = "codeHash", unique = true),
        @Index(name = "idx_oauth_exchange_user", columnList = "userId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthExchangeCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    /** SHA-256 hex of the raw code (see {@code TokenHasher}); raw value never stored. */
    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean consumed = false;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
