package com.gridstore.huevista.maskreport.model;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.project.model.Project;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * "This came out wrong" — one report, raised from the studio after an AI run, read
 * by the admin console.
 *
 * The pipeline can fail without failing: it returns SEGMENTED, the studio shows a
 * canvas, and the walls are simply in the wrong places. Nothing in the system
 * notices that, because from the backend's side the run succeeded. The only party
 * who can see it is the person looking at their own room, so this is the channel
 * that lets them say so.
 *
 * <h2>Who owns a report</h2>
 * Exactly like {@link Project} itself: EITHER a signed-in {@code user} OR — for a
 * walk-in guest working under a shop access code — the {@code accessCode}, with the
 * other left null. A guest hitting a bad mask is the case that matters most (they
 * are standing at a counter), so the guest path is not an afterthought here.
 *
 * <h2>Why the snapshot columns</h2>
 * {@code projectStatus} / {@code maskMode} / {@code regionCount} / {@code hadCleanedImage}
 * record what the pipeline had produced AT THE MOMENT OF THE REPORT. The obvious
 * response to a bad mask is to re-run segmentation, and that overwrites every one of
 * those on the project — so without the snapshot the admin opening the queue a day
 * later is looking at a different run than the one being complained about.
 */
@Entity
@Table(name = "mask_reports", indexes = {
        @Index(name = "idx_mask_reports_status_created", columnList = "status, created_at"),
        @Index(name = "idx_mask_reports_project", columnList = "project_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaskReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The room being complained about. Never null — a report with no project is
     *  support mail, and {@code /api/support} already handles that. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** The signed-in reporter, or null when a guest raised it under an access code. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User reporter;

    /** The access code a guest reporter was working under. Null for signed-in users.
     *  Also how the admin sees WHICH SHOP the complaint came through. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_code_id")
    private CustomerAccessCode accessCode;

    /**
     * The ticked issues, comma-separated {@link MaskReportIssue} names.
     *
     * A joined table would buy nothing here: the set is at most three values, is
     * always read whole with its report, and is never queried across reports. Same
     * shape (and same reasoning) as {@code Project.shareBrands}.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String issues;

    /** What the user typed, if anything. Optional — a ticked box alone is a report. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private MaskReportStatus status = MaskReportStatus.NEW;

    // ─── Snapshot of the run being reported (see class doc) ──────────────────

    /** {@code Project.status} when the report was raised, as a name. */
    @Column(length = 32)
    private String projectStatus;

    /** "AUTO" / "MANUAL" — the wall-creation mode of the reported run. */
    @Column(length = 16)
    private String maskMode;

    /** How many regions the project carried when the report was raised. Zero on a
     *  run that detected nothing, which is itself the most common complaint. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int regionCount = 0;

    /** Whether the clean-up stage had produced an image. False means the cleaner
     *  never ran, which makes "image not cleaned properly" a different bug. */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean hadCleanedImage = false;

    /**
     * "CLEAN" / "MASK" when the reported run had FAILED outright, from
     * {@code Project.failureStage}. Null when the run succeeded — which is the
     * interesting case in its own right, because a report against a SEGMENTED project
     * means the pipeline believed it had done its job and the walls are still wrong.
     */
    @Column(length = 16)
    private String failureStage;

    /** What the run told the user when it failed, kept verbatim so the admin reads the
     *  same sentence the reporter was looking at. Null when the run didn't fail. */
    @Column(columnDefinition = "TEXT")
    private String failureReason;

    // ─── Admin side ──────────────────────────────────────────────────────────

    /** What the admin found / did. Internal — never shown to the reporter. */
    @Column(columnDefinition = "TEXT")
    private String adminNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    private LocalDateTime resolvedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** The ticked issues as a list, ignoring anything unparseable. */
    public List<MaskReportIssue> getIssueList() {
        if (issues == null || issues.isBlank()) return List.of();
        return Arrays.stream(issues.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MaskReport::parseIssue)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Stores the issues, de-duplicated and in the order given. */
    public void setIssueList(List<MaskReportIssue> list) {
        this.issues = list == null ? "" : new LinkedHashSet<>(list).stream()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    /**
     * Tolerant parse. A value this build no longer knows is dropped rather than
     * thrown: a stored report is a record of something that already happened, and
     * failing to READ the queue is a worse outcome than losing one checkbox.
     */
    private static MaskReportIssue parseIssue(String name) {
        try {
            return MaskReportIssue.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
