package com.gridstore.huevista.lead.dto;

import com.gridstore.huevista.lead.model.ShopLead;
import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * One shop-account request as the admin queue shows it. Carries every detail the
 * owner filled in — which is the point: the admin reads the request and presses
 * one button, rather than retyping it into a creation form.
 *
 * <p>Never carries the password, in any form. It is not on the entity's JSON, not
 * here, and there is no endpoint anywhere that returns it.
 */
@Data
@Builder
public class ShopLeadResponse {
    private String id;
    private String name;
    private String email;
    private String phone;
    private String shopName;
    private String city;
    private String state;
    /** Legacy field from the old funnel; always null on new requests. */
    private String tier;
    private String notes;
    private ShopLead.Status status;
    private LocalDateTime createdAt;

    /** True once the owner proved the mailbox with the emailed code. */
    private boolean emailVerified;

    /**
     * True when pressing "Create account" is all that's left — the owner set a
     * password and verified their address. False for requests carried over from the
     * old call-back funnel, which have to be created by hand.
     */
    private boolean readyToCreate;

    /** When this request provisions itself if no admin acts. Null once it has. */
    private LocalDateTime autoApproveAt;

    /**
     * Hours left on that deadline, rounded down — what the queue counts down. Null
     * when there is no deadline; 0 once it has passed but the hourly job hasn't run.
     */
    private Long hoursUntilAutoCreate;

    /** The distributor the resulting shop was filed under, once it exists. */
    private String distributorOrgId;
    private String distributorName;

    /** The RETAILER user this request became. */
    private String createdUserId;
    private LocalDateTime approvedAt;
    /** True when the deadline provisioned it rather than an admin. */
    private boolean autoApproved;

    public static ShopLeadResponse from(ShopLead lead) {
        return from(lead, null);
    }

    public static ShopLeadResponse from(ShopLead lead, String distributorName) {
        LocalDateTime deadline = lead.getAutoApproveAt();
        Long hoursLeft = null;
        if (deadline != null) {
            hoursLeft = Math.max(0, Duration.between(LocalDateTime.now(), deadline).toHours());
        }
        return ShopLeadResponse.builder()
                .id(lead.getId())
                .name(lead.getName())
                .email(lead.getEmail())
                .phone(lead.getPhone())
                .shopName(lead.getShopName())
                .city(lead.getCity())
                .state(lead.getState())
                .tier(lead.getTier())
                .notes(lead.getNotes())
                .status(lead.getStatus())
                .createdAt(lead.getCreatedAt())
                .emailVerified(lead.isEmailVerified())
                .readyToCreate(lead.isProvisionable() && lead.getStatus() == ShopLead.Status.AWAITING_APPROVAL)
                .autoApproveAt(deadline)
                .hoursUntilAutoCreate(hoursLeft)
                .distributorOrgId(lead.getDistributorOrgId())
                .distributorName(distributorName)
                .createdUserId(lead.getCreatedUserId())
                .approvedAt(lead.getApprovedAt())
                .autoApproved(lead.getApprovedAt() != null && lead.getApprovedByUserId() == null)
                .build();
    }
}
