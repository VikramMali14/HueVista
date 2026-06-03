package com.gridstore.huevista.billing.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.CREATED;

    @Column(unique = true)
    private String razorpaySubscriptionId;

    private String razorpayCustomerId;

    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;

    @Column(nullable = false)
    @Builder.Default
    private int aiGenerationsUsed = 0;

    @Column(nullable = false)
    private int aiGenerationsLimit;

    @Builder.Default
    private boolean cancelAtPeriodEnd = false;

    // True for a free trial granted at signup (status ACTIVE, no Razorpay id). The
    // daily expiry job flips it to EXPIRED once currentPeriodEnd passes.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean trial = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
