package com.gridstore.huevista.account.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-customer project entitlement, created/refreshed when a customer redeems a
 * retailer's access code. Governs how many projects the customer may create and
 * until when their access is valid.
 *
 * <ul>
 *   <li>{@code accessExpiresAt} — the day-validity window (now + access-code validDays).</li>
 *   <li>{@code projectAllowance} — total projects the customer may create (default 1;
 *       incremented when the retailer grants one or the customer buys one).</li>
 *   <li>{@code projectsCreated} — monotonic counter; deleting a project does NOT refund a slot.</li>
 * </ul>
 */
@Entity
@Table(name = "customer_entitlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The customer this entitlement belongs to (one per customer). */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_user_id", nullable = false, unique = true)
    private User customer;

    /** The retailer organization that onboarded this customer (the "managed by" link). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retailer_org_id")
    private Organization retailerOrg;

    @Column(nullable = false)
    private LocalDateTime accessExpiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int projectAllowance = 1;

    @Column(nullable = false)
    @Builder.Default
    private int projectsCreated = 0;

    /**
     * Optimistic lock: allowance/usage counters are read-modify-write, so two
     * concurrent grants (or a grant racing a project creation) would otherwise
     * silently lose one of the increments.
     */
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0 not null")
    private long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public boolean isExpired() {
        return accessExpiresAt == null || LocalDateTime.now().isAfter(accessExpiresAt);
    }

    public int getProjectsRemaining() {
        return Math.max(0, projectAllowance - projectsCreated);
    }
}
