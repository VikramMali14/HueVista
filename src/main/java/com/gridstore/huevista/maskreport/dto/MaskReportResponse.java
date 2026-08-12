package com.gridstore.huevista.maskreport.dto;

import com.gridstore.huevista.maskreport.model.MaskReport;
import com.gridstore.huevista.maskreport.model.MaskReportIssue;
import com.gridstore.huevista.maskreport.model.MaskReportStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One report, as the admin queue reads it.
 *
 * Also what the reporter gets back on submit, so the studio can say "we've got it"
 * with the real id — but the reporter's copy is built by {@link #forReporter} and
 * carries none of the admin-only fields.
 */
@Data
@Builder
public class MaskReportResponse {

    private String id;
    private List<MaskReportIssue> issues;
    private String note;
    private MaskReportStatus status;
    private LocalDateTime createdAt;
    /** True when the pipeline filed this itself (wall detection came back empty)
     *  rather than a person reporting what they could see. */
    private boolean autoRaised;

    // ─── Who and what (admin view; null on the reporter's copy) ──────────────

    private String projectId;
    private String projectName;
    /** Who to reply to. Null on a guest report — the shop is the contact instead. */
    private String reporterName;
    private String reporterEmail;
    private String reporterRole;
    /** Set on guest reports: the shop whose code the walk-in was working under. */
    private String shopName;

    // ─── The reported run (see MaskReport's snapshot columns) ────────────────

    private String projectStatus;
    private String maskMode;
    private Integer regionCount;
    private Boolean hadCleanedImage;
    /** "CLEAN" / "MASK" when the reported run failed outright; null when it "succeeded". */
    private String failureStage;
    /** What the failed run told the reporter. Null when the run didn't fail. */
    private String failureReason;

    // ─── Admin handling ──────────────────────────────────────────────────────

    private String adminNote;
    private String resolvedByName;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;

    /** The reporter's own receipt: their words back, and nothing about anyone else. */
    public static MaskReportResponse forReporter(MaskReport r) {
        return MaskReportResponse.builder()
                .id(r.getId())
                .issues(r.getIssueList())
                .note(r.getNote())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .autoRaised(r.isAutoRaised())
                .build();
    }

    /**
     * The admin queue row. Associations are read through the fetch-joined graph the
     * queue query builds; calling this on a detached report would lazy-load.
     */
    public static MaskReportResponse forAdmin(MaskReport r) {
        var project = r.getProject();
        var reporter = r.getReporter();
        var code = r.getAccessCode();
        return MaskReportResponse.builder()
                .id(r.getId())
                .issues(r.getIssueList())
                .note(r.getNote())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .autoRaised(r.isAutoRaised())
                .projectId(project != null ? project.getId() : null)
                .projectName(project != null ? project.getName() : null)
                .reporterName(reporter != null ? reporter.getName() : null)
                .reporterEmail(reporter != null ? reporter.getEmail() : null)
                .reporterRole(reporter != null && reporter.getRole() != null
                        ? reporter.getRole().name() : (code != null ? "GUEST" : null))
                .shopName(code != null && code.getOrganization() != null
                        ? code.getOrganization().getName() : null)
                .projectStatus(r.getProjectStatus())
                .maskMode(r.getMaskMode())
                .regionCount(r.getRegionCount())
                .hadCleanedImage(r.isHadCleanedImage())
                .failureStage(r.getFailureStage())
                .failureReason(r.getFailureReason())
                .adminNote(r.getAdminNote())
                .resolvedByName(r.getResolvedBy() != null ? r.getResolvedBy().getName() : null)
                .resolvedAt(r.getResolvedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
