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

    // Images processed this cycle. Every image consumes one — the AI photo
    // clean-up step is compulsory. (Column names keep the historical
    // "ai_generations" naming for DB compatibility.)
    @Column(nullable = false)
    @Builder.Default
    private int aiGenerationsUsed = 0;

    @Column(nullable = false)
    private int aiGenerationsLimit;

    /**
     * Image credits currently HELD (not yet spent) by outstanding customer access
     * codes — one per assigned project. Held at code-generation time and moved into
     * {@link #aiGenerationsUsed} only when the project is actually segmented; returned
     * to the pool when a code is revoked or expires unredeemed.
     *
     * Deliberately NOT reset on renewal: a code issued in the old cycle is still
     * redeemable in the new one, so its hold must survive the reset (see
     * BillingService#handlePaymentCaptured). Effective availability everywhere is
     * {@code limit + purchasedImageCredits - used - reservedImages}.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int reservedImages = 0;

    /** AI auto-mask (wall-detection) runs this cycle — charged only when the
     *  shop picks the automatic mask after clean-up; manual masking is free. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int autoMasksUsed = 0;

    /** Auto-mask runs allowed per cycle (0 = plan is manual-masking only). */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int autoMasksLimit = 0;

    /** Pay-per-image overage credits bought at Rs. 50 + GST each once the
     *  monthly image quota is spent. NOT reset on renewal — a paid credit
     *  never evaporates; it simply extends the image allowance. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int purchasedImageCredits = 0;

    /** Pay-per-use AI auto-mask credits bought at Rs. 25 + GST each (from the
     *  prepaid billing wallet) once the monthly auto-mask allowance is spent.
     *  NOT reset on renewal, same as purchasedImageCredits. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int purchasedAutoMaskCredits = 0;

    /** Colour-board PDF downloads this billing cycle — reset on renewal like AI usage. */
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
     * so the trial's one-project allowance can't be recycled.
     *
     * The trial gate used to COUNT live projects, which meant deleting the trial project
     * freed the slot and a trial account could create an unlimited number of them one at
     * a time, despite the message promising "your free trial includes one project".
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
