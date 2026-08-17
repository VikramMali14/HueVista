package com.gridstore.huevista.account.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-customer project entitlement, created when a customer redeems a retailer's access
 * code. Governs how many projects the customer may create.
 *
 * <ul>
 *   <li>{@code projectAllowance} — total projects the customer may create (default 1;
 *       incremented when the retailer grants one or tops up a code they hold).</li>
 *   <li>{@code projectsCreated} — monotonic counter; deleting a project does NOT refund a slot.</li>
 * </ul>
 *
 * <h2>There is no expiry here</h2>
 * An entitlement has no window. Once a shop hands a code across the counter and the
 * customer redeems it onto their account, the projects it bought are theirs — they do
 * not lapse, go view-only, or need a fresh code to reopen. The only deadline anywhere in
 * this model sits on an UNREDEEMED code (30 days), and it stops mattering the moment
 * somebody uses it.
 *
 * <p>Projects an account buys for itself are a separate route with its own per-project
 * validity ({@code ProjectAccessService}); nothing here governs those.
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

    public int getProjectsRemaining() {
        return Math.max(0, projectAllowance - projectsCreated);
    }
}
