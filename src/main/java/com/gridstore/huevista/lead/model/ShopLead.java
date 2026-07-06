package com.gridstore.huevista.lead.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A shop owner's request for a HueVista retailer account, submitted from the
 * public "bring it to your counter" form. Shops are provisioned by an admin
 * (never self-serve), so the public funnel captures a lead instead of dead-ending
 * on a mailto link; the admin works the list and creates the account from it.
 */
@Entity
@Table(name = "shop_leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopLead {

    public enum Status { NEW, CONTACTED, CONVERTED, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(nullable = false)
    private String shopName;

    private String city;
    private String state;

    /** Tier the shop showed interest in ("starter" | "pro" | "business" | blank). */
    private String tier;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.NEW;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
