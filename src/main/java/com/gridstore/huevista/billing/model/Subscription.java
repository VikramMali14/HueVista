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

    /**
     * How many of the plan this subscription is billed for. Razorpay multiplies the
     * charge by it, so the quota is multiplied to match.
     *
     * Stored rather than recomputed because renewal has to rebuild the allowance from
     * the plan (that is how a change to a tier's quota reaches existing customers), and
     * without the quantity it could only ever rebuild a single plan's worth — silently
     * cutting a shop paying 3x down to 1x on its next renewal.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 1")
    @Builder.Default
    private int quantity = 1;

    /**
     * Complete projects run this cycle. One project covers the whole automatic
     * pipeline — the compulsory AI photo clean-up AND the AI wall detection — so it is
     * charged once, not once per step.
     */
    @Column(nullable = false)
    @Builder.Default
    private int projectsUsed = 0;

    @Column(nullable = false)
    private int projectsLimit;

    /**
     * Project credits currently HELD (not yet spent) by outstanding customer access
     * codes — one per assigned project. Held at code-generation time and moved into
     * {@link #projectsUsed} only when the project is actually segmented; returned
     * to the pool when a code is revoked or expires unredeemed.
     *
     * Deliberately NOT reset on renewal: a code issued in the old cycle is still
     * redeemable in the new one, so its hold must survive the reset (see
     * BillingService#handlePaymentCaptured). Effective availability everywhere is
     * {@code limit + purchasedProjectCredits + carriedProjectCredits - used - reservedProjects}.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int reservedProjects = 0;

    /**
     * Extra projects bought one at a time at the plan's own rate once the monthly quota
     * is spent. NOT reset on renewal — a paid credit never evaporates; it simply extends
     * the project allowance.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int purchasedProjectCredits = 0;

    /**
     * Projects left over from the plan this one replaced, moved across on upgrade so a
     * shop that upgrades mid-cycle doesn't forfeit what it had already paid for.
     *
     * Unlike {@link #purchasedProjectCredits} these DO expire: they are part of a monthly
     * allowance, so they are zeroed on renewal along with {@link #projectsUsed}. A shop
     * that carries 5 projects into a new plan has that cycle to use them, not forever.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int carriedProjectCredits = 0;

    /** Colour-board PDF downloads this billing cycle — reset on renewal like project usage. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int pdfDownloadsUsed = 0;

    /** Downloads allowed per cycle (scaled by billed quantity; MAX_VALUE = unlimited). */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int pdfDownloadsLimit = 0;

    /** Most coloured snapshots one PDF board may contain (per-document, not scaled). */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int pdfImageLimit = 0;

    /**
     * Projects ever created while this subscription was on its free trial — monotonic,
     * so the trial's project allowance can't be recycled.
     *
     * The trial gate used to COUNT live projects, which meant deleting the trial project
     * freed the slot and a trial account could create an unlimited number of them one at
     * a time, despite the message promising a fixed allowance.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int trialProjectsCreated = 0;

    /**
     * Billing cycles Razorpay has actually charged for. Incremented by
     * {@code subscription.charged} / {@code payment.captured}, so 0 means "authorized
     * but never charged" — the state a subscription scheduled to start at a later date
     * sits in for its whole first month.
     *
     * This replaces guessing a first charge from how long ago the period started, which
     * a scheduled start makes meaningless (the period start is in the FUTURE) and a late
     * {@code subscription.activated} echo could reset under the old code.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int billingCyclesCharged = 0;

    /**
     * The Razorpay payment id of the charge most recently applied to this subscription.
     *
     * One charge reaches us as TWO events — the setup guide has merchants enable both
     * subscription.charged and payment.captured, and Razorpay sends both for the same
     * renewal. Different events with different ids, so the processed-event guard lets both
     * through; it stops a redelivery of one event, not two events describing one charge.
     * Applying both counted two cycles per payment and expired credits an upgrade had just
     * carried across. Recording the payment makes the second one a no-op.
     *
     * NULL means no charge has been applied yet, which is also where every row created
     * before this column existed starts.
     */
    private String lastChargePaymentId;

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

    /**
     * Every project this subscription may still run this cycle before anything is spent:
     * the plan's monthly allowance, plus paid-for extras, plus whatever was carried in
     * from a plan it replaced. Clamped against int overflow (Enterprise is unlimited).
     */
    public int effectiveProjectAllowance() {
        if (projectsLimit == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        long allowance = (long) projectsLimit + purchasedProjectCredits + carriedProjectCredits;
        return allowance > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) allowance;
    }

    /** Projects still free to start — the allowance less what is spent and what is held
     *  behind unredeemed access codes. */
    public int projectsRemaining() {
        if (projectsLimit == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, effectiveProjectAllowance() - projectsUsed - reservedProjects);
    }
}
