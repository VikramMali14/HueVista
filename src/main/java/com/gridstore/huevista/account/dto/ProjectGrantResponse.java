package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.ProjectGrant;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One recorded act of a shop giving projects away, and whether it can still be undone. */
@Data
@Builder
public class ProjectGrantResponse {

    private String id;
    /** Exactly one of these is set: the grant went to a customer, or onto a code. */
    private String customerUserId;
    private String accessCodeId;
    private int projects;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;

    /**
     * Whether "take back" would succeed right now. False once the customer has used the
     * projects, and false after the billing period that funded the grant has renewed —
     * releasing those images into a new period would mint quota the old one paid for.
     */
    private boolean revocable;

    public static ProjectGrantResponse from(ProjectGrant grant, boolean revocable) {
        return ProjectGrantResponse.builder()
                .id(grant.getId())
                .customerUserId(grant.getCustomerUserId())
                .accessCodeId(grant.getAccessCodeId())
                .projects(grant.getProjects())
                .createdAt(grant.getCreatedAt())
                .revokedAt(grant.getRevokedAt())
                .revocable(revocable)
                .build();
    }
}
