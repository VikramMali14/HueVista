package com.gridstore.huevista.auth.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String email;

    // null for OAuth2 users — they have no password
    private String password;

    @Column(nullable = false)
    private String name;

    private String picture;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    // Google's "sub" claim — null for LOCAL users
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(255) not null default 'RETAILER'")
    @Builder.Default
    private UserRole role = UserRole.RETAILER;

    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    // Login brute-force throttling. columnDefinition gives a DB default so the
    // NOT-NULL column backfills on existing rows under ddl-auto=update.
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int failedLoginAttempts = 0;

    /** When set and in the future, login is blocked until this time. */
    private LocalDateTime lockedUntil;

    // Optional mobile number (E.164-ish), captured during phone verification.
    private String phoneNumber;

    // columnDefinition supplies a DB default so adding this NOT NULL column to an
    // existing users table (ddl-auto=update on prod Postgres) backfills cleanly.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean phoneVerified = false;

    // Soft-delete tombstone. When set, the account is deleted: PII has been scrubbed,
    // sessions revoked, and the original email freed for re-registration.
    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
