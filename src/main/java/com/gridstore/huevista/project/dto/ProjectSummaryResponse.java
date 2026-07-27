package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.Project;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectSummaryResponse {

    private String id;
    private String name;
    private String status;
    private String imageId;
    private String imageUrl;
    /** Cleaned (AI photo clean-up) image URL, when one has been produced; null
     *  for projects not yet cleaned. Lets the dashboard show a raw-vs-cleaned
     *  before/after slider without a per-project detail fetch. */
    private String cleanedImageUrl;
    private int regionCount;
    private boolean hasShareLink;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Who this room belongs to, from the reader's point of view:
     * {@code OWN} — they created it themselves; {@code CUSTOMER} — a walk-in or
     * onboarded customer created it under a code this shop issued. The dashboard
     * filters on exactly this, so a shop can look at their own work and their
     * customers' work separately.
     */
    private String source;
    /** For a CUSTOMER room: the name the shop typed when it issued the code. */
    private String customerName;
    /** For a CUSTOMER room: the code it was created under, and the code's id. */
    private String accessCode;
    private String accessCodeId;

    /** Look-but-don't-touch (subscription lapsed, or the project's own validity ran out). */
    private boolean readOnly;
    /** When this room's paid validity ends; null when it has no window of its own. */
    private LocalDateTime accessExpiresAt;

    public static final String SOURCE_OWN = "OWN";
    public static final String SOURCE_CUSTOMER = "CUSTOMER";

    public static ProjectSummaryResponse from(Project project, String imageUrl, String cleanedImageUrl) {
        return ProjectSummaryResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .cleanedImageUrl(cleanedImageUrl)
                .regionCount(project.getRegions().size())
                .hasShareLink(project.getShareToken() != null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .source(SOURCE_OWN)
                .accessExpiresAt(project.getAccessExpiresAt())
                .build();
    }

    /** Mark this row as a customer's room, carrying who it was issued to. */
    public ProjectSummaryResponse asCustomerRoom(String code, String codeId, String customerName) {
        this.source = SOURCE_CUSTOMER;
        this.accessCode = code;
        this.accessCodeId = codeId;
        this.customerName = customerName;
        return this;
    }

    public ProjectSummaryResponse withReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }
}
