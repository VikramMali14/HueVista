package com.gridstore.huevista.maskreport.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.maskreport.dto.CreateMaskReportRequest;
import com.gridstore.huevista.maskreport.dto.MaskReportResponse;
import com.gridstore.huevista.maskreport.model.MaskReport;
import com.gridstore.huevista.maskreport.model.MaskReportIssue;
import com.gridstore.huevista.maskreport.model.MaskReportStatus;
import com.gridstore.huevista.maskreport.repository.MaskReportRepository;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Raising and working "the AI got this wrong" reports.
 *
 * The pipeline has no way to know it produced a bad mask — a run that returns
 * SEGMENTED with the walls in the wrong places is, to every check the backend
 * makes, a success. This service is the only channel through which that failure
 * becomes visible, so its job is to make reporting cheap for the user and the
 * resulting queue readable for the admin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaskReportService {

    /** How many rows the admin queue hands back at once. */
    private static final int QUEUE_LIMIT = 200;

    /** Live statuses — what the queue shows unless asked for everything. */
    private static final List<MaskReportStatus> OPEN_STATUSES =
            List.of(MaskReportStatus.NEW, MaskReportStatus.IN_REVIEW);

    private final MaskReportRepository reportRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final EmailSender emailSender;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.support.email:}")
    private String supportEmail;

    /**
     * Where a report lands. Prefers a dedicated support inbox so these don't have to
     * go to the admin's LOGIN address, and falls back to it when unset — a
     * deployment that hasn't configured SUPPORT_EMAIL still gets its reports.
     */
    private String reportInbox() {
        return (supportEmail != null && !supportEmail.isBlank()) ? supportEmail : adminEmail;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Raising a report
    // ─────────────────────────────────────────────────────────────────────────

    /** A signed-in user reports their own project's run. */
    @Transactional
    public MaskReportResponse report(String userId, String projectId, CreateMaskReportRequest request) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        User reporter = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        MaskReport report = reportRepository
                .findFirstByProjectIdAndReporterIdAndStatusNotOrderByCreatedAtDesc(
                        projectId, userId, MaskReportStatus.RESOLVED)
                .orElseGet(() -> MaskReport.builder().project(project).reporter(reporter).build());

        return save(report, project, request, reporter.getName(), reporter.getEmail());
    }

    /**
     * A walk-in guest reports the run on the project they created under a shop's
     * access code. No account exists, so the code is the owner AND the contact —
     * the admin follows up through the shop that issued it.
     */
    @Transactional
    public MaskReportResponse reportAsGuest(String accessCodeId, String projectId,
                                            CreateMaskReportRequest request) {
        Project project = projectRepository.findByIdAndAccessCodeId(projectId, accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        CustomerAccessCode code = accessCodeRepository.findById(accessCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Access code not found"));

        MaskReport report = reportRepository
                .findFirstByProjectIdAndAccessCodeIdAndReporterIsNullAndStatusNotOrderByCreatedAtDesc(
                        projectId, accessCodeId, MaskReportStatus.RESOLVED)
                .orElseGet(() -> MaskReport.builder().project(project).accessCode(code).build());

        String shop = code.getOrganization() != null ? code.getOrganization().getName() : null;
        String who = code.getCustomerName() != null && !code.getCustomerName().isBlank()
                ? code.getCustomerName() + " (walk-in)"
                : "Walk-in customer";
        return save(report, project, request, who + (shop != null ? " at " + shop : ""), null);
    }

    /**
     * Write the report and tell the inbox.
     *
     * Re-reporting the same project REPLACES the previous open row rather than
     * adding one: the person is describing the same broken room, usually after a
     * re-run came out wrong too, and the snapshot is re-taken so the admin sees the
     * latest state rather than the first attempt.
     */
    private MaskReport snapshot(MaskReport report, Project project, CreateMaskReportRequest request) {
        report.setIssueList(normalizeIssues(request.getIssues()));
        report.setNote(blankToNull(request.getNote()));
        report.setStatus(MaskReportStatus.NEW);
        report.setProjectStatus(project.getStatus() != null ? project.getStatus().name() : null);
        report.setMaskMode(project.getMaskMode());
        report.setRegionCount(project.getRegions() != null ? project.getRegions().size() : 0);
        report.setHadCleanedImage(project.getCleanedImageStorageKey() != null);
        // Which stage gave up, when the run failed outright. This is what tells the
        // admin whether to look at the cleaning models or the mask model — and a report
        // with NO stage is the harder bug: the pipeline thought it had succeeded.
        report.setFailureStage(project.getFailureStage() != null
                ? project.getFailureStage().name() : null);
        report.setFailureReason(project.getFailureReason());
        // A re-report re-opens the row; anything an admin had written about the
        // previous pass is stale, but is kept rather than wiped — it is the only
        // record of what was already looked at.
        report.setResolvedAt(null);
        report.setResolvedBy(null);
        return report;
    }

    private MaskReportResponse save(MaskReport report, Project project, CreateMaskReportRequest request,
                                    String who, String replyTo) {
        MaskReport saved = reportRepository.save(snapshot(report, project, request));
        log.info("Mask report {} raised for project {} ({}) issues={}",
                saved.getId(), project.getId(), saved.getProjectStatus(), saved.getIssues());
        notifyInbox(saved, project, who, replyTo);
        return MaskReportResponse.forReporter(saved);
    }

    /**
     * Best-effort heads-up. A failed mail must never fail the report: the row is
     * already committed and the admin console reads rows, not email — the message is
     * a nudge, not the delivery mechanism.
     */
    private void notifyInbox(MaskReport report, Project project, String who, String replyTo) {
        String inbox = reportInbox();
        if (inbox == null || inbox.isBlank()) return;
        try {
            String issues = report.getIssueList().stream().map(MaskReportService::label)
                    .reduce((a, b) -> a + "; " + b).orElse("(none)");
            emailSender.send(inbox, "HueVista: AI run reported — " + project.getName(), """
                    %s reported a problem with an AI run.

                    Project:  %s (%s)
                    Reported: %s
                    Run:      status=%s mode=%s regions=%d cleaned-image=%s
                    Failed:   %s
                    Contact:  %s

                    Note:
                    %s

                    Open the admin console → Mask reports to review it against the
                    project's masks."""
                    .formatted(who, project.getName(), project.getId(), issues,
                            report.getProjectStatus(),
                            report.getMaskMode() != null ? report.getMaskMode() : "AUTO",
                            report.getRegionCount(),
                            report.isHadCleanedImage() ? "yes" : "no",
                            failureLine(report),
                            replyTo != null ? replyTo : "no account — follow up via the shop",
                            report.getNote() != null ? report.getNote() : "(none)"));
        } catch (Exception e) {
            log.warn("Mask report {} saved but the notification e-mail failed: {}",
                    report.getId(), e.toString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  The admin queue
    // ─────────────────────────────────────────────────────────────────────────

    /** Newest first. {@code includeResolved} adds the closed ones back in. */
    @Transactional(readOnly = true)
    public List<MaskReportResponse> queue(boolean includeResolved) {
        List<MaskReportStatus> statuses = includeResolved
                ? List.of(MaskReportStatus.values())
                : OPEN_STATUSES;
        return reportRepository.findForQueue(statuses, PageRequest.of(0, QUEUE_LIMIT)).stream()
                .map(MaskReportResponse::forAdmin)
                .toList();
    }

    /** How many reports are waiting — for the console's queue badge. */
    @Transactional(readOnly = true)
    public long openCount() {
        return reportRepository.countByStatus(MaskReportStatus.NEW);
    }

    /**
     * Move a report along, optionally leaving a note. Resolving stamps who and when;
     * re-opening (back to NEW or IN_REVIEW) clears that stamp so the queue never
     * shows a live report as having been resolved by someone.
     */
    @Transactional
    public MaskReportResponse updateStatus(String adminUserId, String reportId,
                                           MaskReportStatus status, String adminNote) {
        MaskReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found: " + reportId));
        if (adminNote != null) {
            report.setAdminNote(blankToNull(adminNote));
        }
        if (status != null && status != report.getStatus()) {
            report.setStatus(status);
            if (status == MaskReportStatus.RESOLVED) {
                report.setResolvedAt(LocalDateTime.now());
                report.setResolvedBy(userRepository.findById(adminUserId).orElse(null));
            } else {
                report.setResolvedAt(null);
                report.setResolvedBy(null);
            }
        }
        return MaskReportResponse.forAdmin(reportRepository.save(report));
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * De-duplicated, in a fixed order so two reports ticking the same boxes store the
     * same string. Nulls are dropped; an all-null list is rejected upstream by
     * {@code @NotEmpty}, but a list of nothing but nulls would slip past it.
     */
    private List<MaskReportIssue> normalizeIssues(List<MaskReportIssue> issues) {
        List<MaskReportIssue> clean = Optional.ofNullable(issues).orElseGet(List::of).stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .sorted()
                .toList();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Tell us what went wrong — tick at least one option.");
        }
        return clean;
    }

    /**
     * The failure line of the mail. Says which stage gave up and what the user was
     * told — and, when nothing failed, says THAT explicitly rather than leaving a
     * blank: a report on a run the pipeline considered successful is the case where
     * the only evidence is the reporter's own eyes.
     */
    private static String failureLine(MaskReport report) {
        if (report.getFailureStage() == null && report.getFailureReason() == null) {
            return "no — the run reported success, so the masks are wrong rather than missing";
        }
        String stage = switch (String.valueOf(report.getFailureStage())) {
            case "CLEAN" -> "photo clean-up (no cleaned canvas, so no masks were generated)";
            case "MASK" -> "wall detection (the clean landed; no usable walls came out of it)";
            default -> "unknown stage";
        };
        return stage + (report.getFailureReason() != null ? " — " + report.getFailureReason() : "");
    }

    private static String label(MaskReportIssue issue) {
        return switch (issue) {
            case MASK_NOT_GENERATED_PROPERLY -> "mask not generated properly";
            case IMAGE_NOT_CLEANED_PROPERLY -> "image not cleaned properly";
            case OTHER -> "something else";
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
