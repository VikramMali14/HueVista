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

    /**
     * The deadline for REDEEMING this code — thirty days from the moment the shop
     * created it, and meaningful only while nobody has.
     *
     * <p>Once a customer redeems, this column stops governing anything: their access is
     * permanent (see {@link #isExpired()}). A shop hands a slip across the counter and
     * the customer's work is theirs from then on, so there is no window to run out, no
     * extension to grant, and no view-only state to fall into. What the thirty days
     * protect is only the unclaimed case: a code nobody ever used should not sit
     * redeemable forever.
     */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * The customer's name as the shop typed it — a LABEL on the shop's own list so the
     * counter can tell one printed slip from another.
     *
     * <p>It no longer names an account. Codes are redeemed onto an account the customer
     * already created, and that account carries its own name.
     */
    @Column(length = 120)
    private String customerName;

    // How many projects the redeeming customer may create — set by the retailer at
    // generation and charged against the retailer's monthly image quota. Becomes the
    // customer's entitlement allowance. Defaults to 1 for legacy codes.
    @Column(nullable = false, columnDefinition = "integer not null default 1")
    @Builder.Default
    private int projectQuota = 1;

    // Set when a retailer revokes a code nobody has redeemed yet. A revoked code can
    // never be redeemed. Nothing is refunded — see AccessCodeService#revokeCode.
    private LocalDateTime revokedAt;

    // Individual shop products (ShopProduct UUIDs) the retailer unlocked for this
    // customer, stored comma-separated. Combined with allowedBrands (whole companies):
    // the customer sees the UNION. Empty/null on both means "no restriction".
    @Column(length = 4096)
    private String allowedProductIds;

    /**
     * The customer account this code belongs to, set at redemption and never cleared.
     *
     * <p>A code is redeemed onto an account that already exists — there is no anonymous
     * route and nothing auto-provisions an account any more — so a used code always
     * names a real, reachable customer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_user_id")
    private User usedByUser;

    private LocalDateTime usedAt;

    // True when the END CUSTOMER paid for this code, not the shop — today that means a
    // kiosk code bought at the public store link. The shop's plan is not part of that
    // transaction, so runs under this code neither draw on the shop's monthly quota nor
    // are gated by it. Both halves matter: charging the shop spends credits it never
    // agreed to spend on a walk-in, and GATING on the shop meant a customer could pay at
    // the kiosk and then be refused because the shop's own subscription had lapsed.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean selfFunded = false;

    /**
     * The address the walk-in gave at the kiosk till, normalized. Null for a code the
     * shop issued at the counter — there is no buyer on those, only a customer name.
     *
     * <p>This is the customer's way back to what they paid for, and the reason the
     * printed code is not. A slip that never expires is a password anyone who picks it
     * up can use; an e-mailed sign-in code proves the person asking is the person who
     * bought. So re-entry resolves through here, and the 8 characters stay what they
     * were always meant to be — the reference the SHOP reads at the counter.
     *
     * <p>Deliberately kept on the code rather than only on the account: the buyer's
     * address may already belong to somebody else's account (a shop owner buying at
     * their own kiosk), in which case the guest account holds a synthetic address and
     * this is the only record of where the receipt should go.
     */
    @Column(length = 320)
    private String buyerEmail;

    // Colour-board PDFs taken under a self-funded code, counted here rather than against
    // the shop's monthly PDF limit — the customer already paid for the board along with
    // the project. Unused (and left at zero) for ordinary shop-issued codes.
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int pdfDownloadsUsed = 0;

    // Paint companies (brand display names) the shop has unlocked for this customer,
    // stored comma-separated. Empty/null means "no restriction" — every brand is browsable.
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

    /**
     * Whether the window for REDEEMING this code has closed.
     *
     * <p>A redeemed code is never expired. The customer's access does not run out, so
     * asking whether their code has "expired" after they have used it is asking the
     * wrong question — the only deadline in this model is the one on an unclaimed slip.
     *
     * <p>Null-safe for an unredeemed row with no deadline (legacy data, or a partially
     * built code): that reads as expired rather than throwing, because this is called
     * from the public redemption path where an NPE would surface as a 500 instead of a
     * clear refusal.
     */
    public boolean isExpired() {
        if (isUsed()) return false;
        return expiresAt == null || LocalDateTime.now().isAfter(expiresAt);
    }

    /** Single-use: consumed once a customer account redeems it. */
    public boolean isUsed() {
        return usedByUser != null;
    }

    /** Revoked by the shop before redemption — can never be redeemed. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Redeemable right now: not already used, not revoked, not past its 30 days. */
    public boolean isRedeemable() {
        return !isUsed() && !isRevoked() && !isExpired();
    }
}
