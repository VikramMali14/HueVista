package com.gridstore.huevista.account.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "customer_access_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerAccessCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(unique = true, nullable = false, length = 8)
    private String code;

    @Column(nullable = false)
    private int validDays; // fixed 10-day window for retailer-assigned codes

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    // The customer's name, entered by the retailer when the code is generated. Used
    // as the display name of the auto-provisioned CUSTOMER account at redeem time, so
    // the customer sees their own name in the header after redeeming.
    @Column(length = 120)
    private String customerName;

    // How many projects the redeeming customer may create — set by the retailer at
    // generation and charged against the retailer's monthly image quota. Becomes the
    // customer's entitlement allowance. Defaults to 1 for legacy codes.
    @Column(nullable = false, columnDefinition = "integer not null default 1")
    @Builder.Default
    private int projectQuota = 1;

    // Individual shop products (ShopProduct UUIDs) the retailer unlocked for this
    // customer, stored comma-separated. Combined with allowedBrands (whole companies):
    // the customer sees the UNION. Empty/null on both means "no restriction".
    @Column(length = 4096)
    private String allowedProductIds;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_user_id")
    private User usedByUser;

    private LocalDateTime usedAt;

    // True when the code was redeemed by an anonymous guest (no account). usedByUser
    // stays null in that case; usedAt is still set. Lets the shop tell guest
    // redemptions apart, and keeps isUsed() correct for single-use enforcement.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean guestRedeemed = false;

    // Paint companies (brand display names) the shop has unlocked for this guest, stored
    // comma-separated. Empty/null means "no restriction" — the guest may browse every brand.
    // The guest only ever sees these brands in the studio; real shade codes stay hidden.
    @Column(length = 512)
    private String allowedBrands;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** Allowed brand names as a list. Empty list means no restriction (all brands). */
    public List<String> getAllowedBrandList() {
        if (allowedBrands == null || allowedBrands.isBlank()) return List.of();
        return Arrays.stream(allowedBrands.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setAllowedBrandList(List<String> brands) {
        if (brands == null || brands.isEmpty()) {
            this.allowedBrands = null;
            return;
        }
        this.allowedBrands = brands.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    /** Individual unlocked product ids as a list. Empty list means none selected individually. */
    public List<String> getAllowedProductIdList() {
        if (allowedProductIds == null || allowedProductIds.isBlank()) return List.of();
        return Arrays.stream(allowedProductIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setAllowedProductIdList(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            this.allowedProductIds = null;
            return;
        }
        this.allowedProductIds = productIds.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /** Single-use: consumed once a real user redeems it OR a guest redeems it (usedAt set). */
    public boolean isUsed() {
        return usedByUser != null || usedAt != null;
    }
}
