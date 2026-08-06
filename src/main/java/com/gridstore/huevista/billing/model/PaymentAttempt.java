package com.gridstore.huevista.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * One trip through a Razorpay Checkout, recorded whether or not any money moved.
 *
 * <p>Everything else in the billing schema is a record of a SUCCESSFUL payment —
 * {@code SubscriptionPayment}, {@code PointsPurchase}, {@code StorePayment} all exist
 * because a signature verified. That leaves the most common support question with no
 * evidence behind it at all: a shop says "I paid and got nothing", or the month's
 * revenue is down, and the only trace of the buyer who opened Checkout and closed it
 * is a line in the application log that has since rotated away.
 *
 * <p>This row is that evidence. It is opened the moment an order or subscription is
 * created — before the buyer can possibly have paid — and then moved along as the
 * browser reports what happened. It keeps the things a log line does not: the exact
 * page the buyer clicked Pay on, their IP and browser, the amount they were quoted,
 * the gateway's own error code, and the timestamp of each step.
 *
 * <p>It is deliberately append-mostly: {@link #timeline} accumulates every transition
 * so a row can be read as a story after the fact, and the status columns are never
 * cleared once set. Nothing here is used to grant an entitlement — the real purchase
 * tables still own that — so a corrupt or spoofed attempt row can mislead an admin
 * but can never hand out a plan.
 */
@Entity
@Table(name = "payment_attempts", indexes = {
        @Index(name = "idx_attempt_reference", columnList = "reference", unique = true),
        @Index(name = "idx_attempt_user", columnList = "user_id"),
        @Index(name = "idx_attempt_status", columnList = "status"),
        @Index(name = "idx_attempt_flow", columnList = "flow"),
        @Index(name = "idx_attempt_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * The Razorpay id this attempt is keyed by — an {@code order_…} for the one-off
     * flows, a {@code sub_…} for a subscription. Unique, because it is also what the
     * browser quotes when reporting an event: two attempts sharing a reference would
     * make those reports ambiguous.
     */
    @Column(nullable = false, unique = true)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentFlow flow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentAttemptStatus status;

    /** Buyer, when there is a signed-in one. Null for a walk-in kiosk customer. */
    @Column(name = "user_id")
    private String userId;

    /**
     * Denormalized on purpose: an attempt is most useful in exactly the case where the
     * account has since been deleted, and a dangling id would name nobody.
     */
    @Column(name = "user_email")
    private String userEmail;

    /** Owning shop, for a kiosk sale — the walk-in has no account to attribute it to. */
    @Column(name = "organization_id")
    private String organizationId;

    /** What the buyer was quoted, in paise. The money that did or didn't arrive. */
    @Column(name = "amount_paise", nullable = false)
    @Builder.Default
    private int amountPaise = 0;

    @Column(length = 8)
    @Builder.Default
    private String currency = "INR";

    /** Human-readable "what was being bought", e.g. "Professional plan" or "5,000 points". */
    @Column(length = 200)
    private String description;

    /** Plan name where one applies — the tier bought, or the tier the price came from. */
    @Column(length = 32)
    private String plan;

    /** Razorpay payment id, once there is one (success OR a failed charge attempt). */
    @Column(name = "payment_id")
    private String paymentId;

    // ---- Forensics: where the buyer actually was ----------------------------------

    /**
     * The page the buyer clicked Pay on, reported by the browser.
     *
     * <p>This is the field that answers "which button was it?" — /plan, /pricing, the
     * quota wall inside the visualizer and a store kiosk all open a Checkout, and until
     * this was recorded there was no way to tell them apart after the fact.
     */
    @Column(name = "page_url", length = 1024)
    private String pageUrl;

    /** Where the buyer came from before that page, when the browser will say. */
    @Column(length = 1024)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Resolved the same way the rate limiters resolve it, so the two agree. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    // ---- Forensics: what went wrong ------------------------------------------------

    /** Razorpay's own error code, e.g. BAD_REQUEST_ERROR. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_description", length = 512)
    private String errorDescription;

    /** Gateway's blame: customer / business / bank / gateway. */
    @Column(name = "error_source", length = 64)
    private String errorSource;

    /** Which step it died at: payment_initiation, payment_authentication, … */
    @Column(name = "error_step", length = 64)
    private String errorStep;

    @Column(name = "error_reason", length = 128)
    private String errorReason;

    /**
     * Our own note when the failure is on this side — the verification message, or the
     * exception a verify endpoint threw. Kept apart from the gateway's fields so it is
     * always clear which side of the wire produced the complaint.
     */
    @Column(name = "failure_note", columnDefinition = "TEXT")
    private String failureNote;

    /**
     * Every transition, one per line, oldest first: {@code time  STATUS  note}.
     *
     * <p>A single status column can only ever say where an attempt ended up. Support
     * questions are almost always about the path — did they open Checkout twice, did the
     * card fail before they gave up, how long did they sit there — so the path is kept.
     */
    @Column(columnDefinition = "TEXT")
    private String timeline;

    // ---- When --------------------------------------------------------------------

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When Checkout actually appeared. Null = the buyer never saw a payment window. */
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    /** When the attempt reached a terminal status. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** Seconds from order creation to the attempt closing; null while still open. */
    public Long durationSeconds() {
        if (createdAt == null || closedAt == null) return null;
        return Duration.between(createdAt, closedAt).toSeconds();
    }

    /** Rupees, for display — the report is read by people who think in rupees. */
    public double amountInRupees() {
        return amountPaise / 100.0;
    }
}
