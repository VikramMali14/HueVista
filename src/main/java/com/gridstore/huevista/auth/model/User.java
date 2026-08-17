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

    /**
     * BCrypt hash; null for OAuth2 users, who have no password.
     *
     * Marked write-only for JSON so the field cannot ride out on a response even if
     * this entity is ever serialized directly instead of through a DTO. Nothing reads
     * it but the authentication manager, and no endpoint returns it — not to the owner
     * and not to an admin.
     */
    @com.fasterxml.jackson.annotation.JsonProperty(access =
            com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
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

    /**
     * Set when this account was folded into another one and retired — today that means a
     * kiosk guest account the customer merged into their real account. The row stays
     * (tombstoned via {@link #deletedAt}) rather than being deleted, because the shop's
     * access code still points at it in its own records and support needs to be able to
     * answer "where did that walk-in's room go".
     */
    @Column(name = "merged_into_user_id")
    private String mergedIntoUserId;

    // Hierarchy provenance: the user who provisioned this account (admin → distributor
    // → retailer → painter). Null for self-signups and pre-hierarchy accounts.
    @Column(name = "created_by_user_id")
    private String createdById;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
