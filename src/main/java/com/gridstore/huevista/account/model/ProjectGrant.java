package com.gridstore.huevista.account.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One act of a shop giving somebody more projects — and the record that makes it
 * reversible.
 *
 * Two things travel with every grant. The projects themselves, so the shop can take back
 * what nobody used; and the subscription PERIOD the images were reserved against, so they
 * can only be taken back while that period is still running.
 *
 * <h2>Why the period is the boundary</h2>
 * Images reserved in March came out of March's allowance. Releasing them in April would
 * hand April a credit March paid for — quota created out of nothing, once per renewal,
 * for as long as anyone kept granting and revoking. So a grant stops being revocable the
 * moment its subscription renews, whether or not the customer ever used it. That is the
 * rule the product owner asked for, and it is also the only one that balances.
 */
@Entity
@Table(name = "project_grants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The shop that gave them, and whose quota paid. */
    @Column(name = "retailer_org_id", nullable = false)
    private String retailerOrgId;

    /** Set when the grant topped up a customer's allowance directly. */
    @Column(name = "customer_user_id")
    private String customerUserId;

    /** Set when the grant added projects to a code the customer holds. */
    @Column(name = "access_code_id")
    private String accessCodeId;

    @Column(nullable = false)
    private int projects;

    /**
     * The subscription the images were reserved against, and where that subscription was
     * in its cycle at the time. Both must still match for a revoke to be allowed.
     */
    private String subscriptionId;

    private LocalDateTime periodStart;

    private LocalDateTime revokedAt;

    private String revokedByUserId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Is this grant still funded by the period it was made in?
     *
     * A null subscription id means the grant predates this ledger and nothing can be
     * proven about what paid for it — those are never revocable, because guessing wrong
     * releases quota that was never reserved.
     */
    public boolean fundedBy(String currentSubscriptionId, LocalDateTime currentPeriodStart) {
        if (subscriptionId == null || !subscriptionId.equals(currentSubscriptionId)) {
            return false;
        }
        if (periodStart == null || currentPeriodStart == null) {
            return false;
        }
        // Compared at second granularity, not exactly. The value written here comes from a
        // live entity with nanosecond precision while the one read back has been through a
        // timestamp column, so an exact equals answers "different period" for the period
        // it was literally just taken from — every grant would become unrevocable the
        // moment it was reloaded. A billing period start is never meaningfully sub-second.
        return periodStart.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .equals(currentPeriodStart.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
}
