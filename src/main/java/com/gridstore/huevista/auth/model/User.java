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

    // Optional mobile number (E.164-ish), captured during phone verification.
    private String phoneNumber;

    // columnDefinition supplies a DB default so adding this NOT NULL column to an
    // existing users table (ddl-auto=update on prod Postgres) backfills cleanly.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean phoneVerified = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
