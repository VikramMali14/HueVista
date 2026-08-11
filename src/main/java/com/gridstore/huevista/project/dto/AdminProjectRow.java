package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.project.model.Project;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One room in the admin's platform-wide list, carrying WHOSE it is.
 *
 * <p>Deliberately not {@link ProjectSummaryResponse}. That one describes a room from the
 * point of view of somebody who owns or issued it, and its {@code source} field says
 * "mine" or "my customer's" — categories that mean nothing to an admin, who owns none of
 * them. What an admin needs instead is identification: which account, which shop, which
 * code, so the room a user reported can actually be found among everyone's.
 *
 * <p>Owner and shop are both nullable and both are shown, because a room can be owned by
 * a registered user, by a walk-in's access code alone, or by a user who signed up after
 * starting as a walk-in and so has both.
 */
@Data
@Builder
public class AdminProjectRow {

    private String id;
    private String name;
    private String status;
    /** "AUTO" / "MANUAL" — null means the default AUTO. */
    private String maskMode;
    private int regionCount;
    /** Whether the photo clean-up produced a canvas. False means the masks sit on the original. */
    private boolean hasCleanedImage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ─── Whose room this is ──────────────────────────────────────────────────

    /** The registered owner, when there is one. Null for a walk-in's room. */
    private String ownerId;
    private String ownerName;
    private String ownerEmail;
    private String ownerRole;
    /** The shop whose code the room was created under, when it was. */
    private String shopName;
    private String accessCode;
    /** The name the shop typed when it issued that code. */
    private String customerName;

    public static AdminProjectRow from(Project project) {
        User owner = project.getUser();
        CustomerAccessCode code = project.getAccessCode();
        return AdminProjectRow.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus() != null ? project.getStatus().name() : null)
                .maskMode(project.getMaskMode())
                .regionCount(project.getRegions() != null ? project.getRegions().size() : 0)
                .hasCleanedImage(project.getCleanedImageStorageKey() != null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .ownerId(owner != null ? owner.getId() : null)
                .ownerName(owner != null ? owner.getName() : null)
                .ownerEmail(owner != null ? owner.getEmail() : null)
                .ownerRole(owner != null && owner.getRole() != null ? owner.getRole().name() : null)
                .shopName(code != null && code.getOrganization() != null
                        ? code.getOrganization().getName() : null)
                .accessCode(code != null ? code.getCode() : null)
                .customerName(code != null ? code.getCustomerName() : null)
                .build();
    }
}
