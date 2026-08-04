package com.gridstore.huevista.lead.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A shop owner's request for a HueVista account, submitted from the public
 * "bring it to your counter" form.
 *
 * <p>The request carries everything the account needs, so provisioning it is one
 * click for the admin and needs nothing from the shop a second time: the owner
 * chooses their own password here (entered twice, stored only as a BCrypt hash)
 * and proves the mailbox is theirs with a 6-digit code before the request is
 * queued at all. A verified request that nobody has looked at within
 * {@link #getAutoApproveAt()} provisions itself, so a shop is never waiting on an
 * admin who is asleep.
 *
 * <p>The password is write-only in every direction — never serialized to JSON,
 * never in {@code toString()}, never logged, and never readable by an admin. The
 * only thing done with it is {@code matches()} at sign-in, after the account
 * exists.
 *
 * <p>No plan is requested here. Every shop is created on the free tier; paid
 * plans are only ever reached by buying one.
 */
@Entity
@Table(name = "shop_leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ShopLead {

    /**
     * Where a request is in the funnel.
     *
     * <p>{@code PENDING_EMAIL} → {@code AWAITING_APPROVAL} → {@code APPROVED} is the
     * live flow. {@code NEW}/{@code CONTACTED}/{@code CONVERTED} are the statuses of
     * the older call-back funnel and survive only so rows written before this existed
     * still load; nothing sets them any more. Those rows carry no password and no
     * verified email, which is what keeps them out of the one-click and automatic
     * provisioning paths.
     */
    public enum Status {
        /** Submitted, mailbox not yet proven. Invisible to the admin queue. */
        PENDING_EMAIL,
        /** Email verified. Waiting for an admin — or for the 24-hour deadline. */
        AWAITING_APPROVAL,
        /** Account provisioned from this request. */
        APPROVED,
        /** Turned down by an admin. */
        DISMISSED,

        // ── legacy, read-only ─────────────────────────────────────────────
        NEW, CONTACTED, CONVERTED
    }

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

    /**
     * Legacy: the tier the shop said it was interested in. The form no longer asks —
     * a plan is bought, not requested — and nothing writes this any more. Kept so
     * historic rows still load.
     */
    private String tier;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING_EMAIL;

    // ── The owner's credential ────────────────────────────────────────────

    /**
     * BCrypt hash of the password the owner chose. Excluded from JSON and from
     * {@code toString()} so it cannot leak into an API response or a log line even
     * by accident — the whole object is safe to log.
     */
    @JsonIgnore
    @ToString.Exclude
    @Column(name = "password_hash")
    private String passwordHash;

    // ── Email verification ────────────────────────────────────────────────

    /** BCrypt hash of the 6-digit code last emailed. Never exposed. */
    @JsonIgnore
    @ToString.Exclude
    @Column(name = "verification_code_hash")
    private String verificationCodeHash;

    private LocalDateTime verificationExpiresAt;

    /** Codes sent, and wrong tries against the current one — both throttles. */
    @Builder.Default
    private int verificationAttempts = 0;

    private LocalDateTime verificationSentAt;

    /** When the owner proved the mailbox. Null until they do. */
    private LocalDateTime emailVerifiedAt;

    // ── Provisioning ──────────────────────────────────────────────────────

    /**
     * When this request provisions itself if no admin has acted. Set at verification
     * (24 hours out) and cleared once the account exists. Null on legacy rows, which
     * is what keeps them out of the automatic path.
     */
    private LocalDateTime autoApproveAt;

    /** The distributor org the resulting shop was filed under. */
    private String distributorOrgId;

    /** The RETAILER user this request became. */
    private String createdUserId;

    private LocalDateTime approvedAt;

    /** The admin who approved it — null when the 24-hour deadline did. */
    private String approvedByUserId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** True once the mailbox is proven — the gate on everything downstream. */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /**
     * True when this request can become an account on its own, with no further
     * input: the owner set a password and proved their address. Legacy rows are
     * false, so neither the one-click button nor the deadline touches them.
     */
    public boolean isProvisionable() {
        return isEmailVerified() && passwordHash != null && !passwordHash.isBlank();
    }
}
