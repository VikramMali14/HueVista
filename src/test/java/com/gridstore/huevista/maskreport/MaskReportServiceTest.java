package com.gridstore.huevista.maskreport;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.maskreport.dto.CreateMaskReportRequest;
import com.gridstore.huevista.maskreport.model.MaskReport;
import com.gridstore.huevista.maskreport.model.MaskReportIssue;
import com.gridstore.huevista.maskreport.model.MaskReportStatus;
import com.gridstore.huevista.maskreport.repository.MaskReportRepository;
import com.gridstore.huevista.maskreport.service.MaskReportService;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contract of "the AI got this wrong".
 *
 * The behaviours pinned here are the ones that make the queue usable rather than
 * the ones that make a row exist: the run is SNAPSHOTTED (because the obvious
 * response to a bad mask overwrites the project), a repeat press UPDATES rather
 * than duplicates, and a mail failure never costs the report.
 */
class MaskReportServiceTest {

    private final MaskReportRepository reports = mock(MaskReportRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CustomerAccessCodeRepository codes = mock(CustomerAccessCodeRepository.class);
    private final EmailSender email = mock(EmailSender.class);

    private final MaskReportService service =
            new MaskReportService(reports, projects, users, codes, email);

    @BeforeEach
    void wireInbox() {
        ReflectionTestUtils.setField(service, "supportEmail", "support@huevista.org");
        ReflectionTestUtils.setField(service, "adminEmail", "admin@huevista.org");
        when(reports.save(any(MaskReport.class))).thenAnswer(inv -> {
            MaskReport r = inv.getArgument(0);
            if (r.getId() == null) r.setId("rep-1");
            return r;
        });
    }

    private static User user() {
        User u = new User();
        u.setId("user-1");
        u.setName("Asha Rao");
        u.setEmail("asha@example.com");
        return u;
    }

    private static Project project() {
        Project p = Project.builder()
                .id("proj-1")
                .name("Front bedroom")
                .status(ProjectStatus.SEGMENTED)
                .maskMode("AUTO")
                .regions(new ArrayList<>(List.of(new Region(), new Region())))
                .build();
        p.setCleanedImageStorageKey("cleaned/proj-1.jpg");
        return p;
    }

    private static CreateMaskReportRequest request(String note, MaskReportIssue... issues) {
        CreateMaskReportRequest r = new CreateMaskReportRequest();
        r.setIssues(new ArrayList<>(List.of(issues)));
        r.setNote(note);
        return r;
    }

    private void ownedByUser(Project p) {
        when(projects.findByIdAndUserId(p.getId(), "user-1")).thenReturn(Optional.of(p));
        when(users.findById("user-1")).thenReturn(Optional.of(user()));
        when(reports.findFirstByProjectIdAndReporterIdAndStatusNotOrderByCreatedAtDesc(
                p.getId(), "user-1", MaskReportStatus.RESOLVED)).thenReturn(Optional.empty());
    }

    private MaskReport saved() {
        ArgumentCaptor<MaskReport> captor = ArgumentCaptor.forClass(MaskReport.class);
        verify(reports).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void records_the_ticked_issues_and_the_note() {
        Project p = project();
        ownedByUser(p);

        service.report("user-1", "proj-1",
                request("  the ceiling was painted  ",
                        MaskReportIssue.MASK_NOT_GENERATED_PROPERLY,
                        MaskReportIssue.IMAGE_NOT_CLEANED_PROPERLY));

        MaskReport r = saved();
        assertThat(r.getIssueList()).containsExactlyInAnyOrder(
                MaskReportIssue.MASK_NOT_GENERATED_PROPERLY,
                MaskReportIssue.IMAGE_NOT_CLEANED_PROPERLY);
        assertThat(r.getNote()).isEqualTo("the ceiling was painted");
        assertThat(r.getStatus()).isEqualTo(MaskReportStatus.NEW);
        assertThat(r.getReporter().getId()).isEqualTo("user-1");
    }

    /**
     * The point of the snapshot. Re-running segmentation is the first thing anyone
     * does with a bad mask, and it rewrites status / mode / region count / cleaned
     * image on the project — so a queue reading live state would describe a
     * different run than the one that was reported.
     */
    @Test
    void snapshots_the_run_being_reported() {
        Project p = project();
        ownedByUser(p);

        service.report("user-1", "proj-1", request(null, MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));

        MaskReport r = saved();
        assertThat(r.getProjectStatus()).isEqualTo("SEGMENTED");
        assertThat(r.getMaskMode()).isEqualTo("AUTO");
        assertThat(r.getRegionCount()).isEqualTo(2);
        assertThat(r.isHadCleanedImage()).isTrue();
    }

    @Test
    void a_run_that_found_nothing_snapshots_as_zero_regions() {
        Project p = project();
        p.setRegions(new ArrayList<>());
        p.setCleanedImageStorageKey(null);
        ownedByUser(p);

        service.report("user-1", "proj-1", request(null, MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));

        MaskReport r = saved();
        assertThat(r.getRegionCount()).isZero();
        assertThat(r.isHadCleanedImage()).isFalse();
    }

    /**
     * Someone unhappy with a mask presses the button again after the re-run is also
     * wrong. That is the same complaint, and stacking it would bury the queue in
     * near-identical tickets about one room.
     */
    @Test
    void reporting_twice_updates_the_open_report_instead_of_filing_a_second() {
        Project p = project();
        when(projects.findByIdAndUserId("proj-1", "user-1")).thenReturn(Optional.of(p));
        when(users.findById("user-1")).thenReturn(Optional.of(user()));
        MaskReport existing = MaskReport.builder()
                .id("rep-existing").project(p).reporter(user())
                .status(MaskReportStatus.IN_REVIEW)
                .adminNote("looked at the raw mask")
                .build();
        existing.setIssueList(List.of(MaskReportIssue.OTHER));
        when(reports.findFirstByProjectIdAndReporterIdAndStatusNotOrderByCreatedAtDesc(
                "proj-1", "user-1", MaskReportStatus.RESOLVED)).thenReturn(Optional.of(existing));

        service.report("user-1", "proj-1",
                request("still wrong", MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));

        MaskReport r = saved();
        assertThat(r.getId()).isEqualTo("rep-existing");
        assertThat(r.getIssueList()).containsExactly(MaskReportIssue.MASK_NOT_GENERATED_PROPERLY);
        assertThat(r.getNote()).isEqualTo("still wrong");
        // Back to the top of the queue — it is unresolved again.
        assertThat(r.getStatus()).isEqualTo(MaskReportStatus.NEW);
        // ...but what was already investigated is not thrown away.
        assertThat(r.getAdminNote()).isEqualTo("looked at the raw mask");
    }

    @Test
    void a_project_that_is_not_yours_is_not_reportable() {
        when(projects.findByIdAndUserId("proj-9", "user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report("user-1", "proj-9",
                request(null, MaskReportIssue.MASK_NOT_GENERATED_PROPERLY)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(reports, never()).save(any());
    }

    @Test
    void a_report_naming_no_problem_is_refused() {
        Project p = project();
        ownedByUser(p);
        CreateMaskReportRequest empty = new CreateMaskReportRequest();
        empty.setIssues(new ArrayList<>());

        // @NotEmpty catches this at the controller; the service refuses it too, so
        // the rule doesn't depend on which door the request came through.
        assertThatThrownBy(() -> service.report("user-1", "proj-1", empty))
                .isInstanceOf(IllegalArgumentException.class);

        verify(reports, never()).save(any());
    }

    /** The report is the deliverable; the mail is a nudge. Losing the nudge must
     *  not lose the report — the admin console reads rows, not inboxes. */
    @Test
    void a_failed_notification_email_does_not_lose_the_report() {
        Project p = project();
        ownedByUser(p);
        doThrow(new RuntimeException("SMTP down"))
                .when(email).send(anyString(), anyString(), anyString());

        var response = service.report("user-1", "proj-1",
                request(null, MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));

        assertThat(response.getId()).isEqualTo("rep-1");
        verify(reports).save(any(MaskReport.class));
    }

    @Test
    void a_guest_report_is_filed_against_the_code_and_names_the_shop() {
        Project p = project();
        Organization org = new Organization();
        org.setId("org-1");
        org.setName("Mehta Paint House");
        CustomerAccessCode code = CustomerAccessCode.builder()
                .id("code-1").organization(org).customerName("Ravi").build();
        when(projects.findByIdAndAccessCodeId("proj-1", "code-1")).thenReturn(Optional.of(p));
        when(codes.findById("code-1")).thenReturn(Optional.of(code));
        when(reports.findFirstByProjectIdAndAccessCodeIdAndReporterIsNullAndStatusNotOrderByCreatedAtDesc(
                "proj-1", "code-1", MaskReportStatus.RESOLVED)).thenReturn(Optional.empty());

        service.reportAsGuest("code-1", "proj-1",
                request("walls all over the sofa", MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));

        MaskReport r = saved();
        assertThat(r.getReporter()).isNull();
        assertThat(r.getAccessCode().getId()).isEqualTo("code-1");

        // The shop is the contact — there is no account to reply to.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("Mehta Paint House");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  The report nobody files: the pipeline's own
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void the_pipeline_files_its_own_report_against_the_owner_when_detection_finds_nothing() {
        // A run whose clean succeeded and whose walls didn't hands the customer a
        // working room, so the customer has no reason to complain — and the mask
        // model's bad afternoon would otherwise reach this queue as silence.
        Project p = project();
        p.setRegions(new ArrayList<>());
        User owner = user();
        p.setUser(owner);
        when(projects.findById("proj-1")).thenReturn(Optional.of(p));
        when(reports.findFirstByProjectIdAndReporterIdAndStatusNotOrderByCreatedAtDesc(
                "proj-1", "user-1", MaskReportStatus.RESOLVED)).thenReturn(Optional.empty());

        service.reportAutoMaskFailure("proj-1");

        MaskReport r = saved();
        assertThat(r.isAutoRaised()).isTrue();
        // Filed against the owner, because that is who an admin follows up with — and
        // because a real complaint from them then folds into this row instead of
        // sitting beside it as a near-duplicate.
        assertThat(r.getReporter().getId()).isEqualTo("user-1");
        assertThat(r.getIssueList()).containsExactly(MaskReportIssue.MASK_NOT_GENERATED_PROPERLY);
        assertThat(r.getRegionCount()).isZero();
        assertThat(r.getNote()).contains("Raised automatically");

        // The mail has to say the run reported ITSELF; read as an ordinary complaint it
        // would send an admin looking for a person who never wrote in.
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), subject.capture(), anyString());
        assertThat(subject.getValue()).contains("reported itself");
    }

    @Test
    void a_guests_empty_run_is_reported_against_the_shops_code() {
        // No account exists, so the code is both the owner and the trail back to the
        // shop — the same routing a guest's own report gets.
        Project p = project();
        p.setRegions(new ArrayList<>());
        Organization org = new Organization();
        org.setId("org-1");
        org.setName("Mehta Paint House");
        p.setAccessCode(CustomerAccessCode.builder().id("code-1").organization(org).build());
        when(projects.findById("proj-1")).thenReturn(Optional.of(p));
        when(reports.findFirstByProjectIdAndAccessCodeIdAndReporterIsNullAndStatusNotOrderByCreatedAtDesc(
                "proj-1", "code-1", MaskReportStatus.RESOLVED)).thenReturn(Optional.empty());

        service.reportAutoMaskFailure("proj-1");

        MaskReport r = saved();
        assertThat(r.isAutoRaised()).isTrue();
        assertThat(r.getReporter()).isNull();
        assertThat(r.getAccessCode().getId()).isEqualTo("code-1");
    }

    @Test
    void a_person_reporting_the_same_project_takes_the_pipelines_row_over() {
        // The pipeline's report says "detection returned nothing". Somebody then looks
        // at the room and writes in about it, and that is a strictly better piece of
        // evidence — same complaint, so it updates the row rather than stacking a
        // second, and the row stops claiming nobody was there to see it.
        Project p = project();
        ownedByUser(p);
        MaskReport existing = MaskReport.builder()
                .id("rep-1").project(p).reporter(user()).build();
        existing.setAutoRaised(true);
        when(reports.findFirstByProjectIdAndReporterIdAndStatusNotOrderByCreatedAtDesc(
                "proj-1", "user-1", MaskReportStatus.RESOLVED)).thenReturn(Optional.of(existing));

        service.report("user-1", "proj-1",
                request("the walls it did find are on the curtains",
                        MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));

        MaskReport r = saved();
        assertThat(r.getId()).isEqualTo("rep-1");
        assertThat(r.isAutoRaised()).isFalse();
        assertThat(r.getNote()).isEqualTo("the walls it did find are on the curtains");
    }

    @Test
    void an_ownerless_project_is_skipped_rather_than_filed_against_nobody() {
        Project p = project();
        p.setRegions(new ArrayList<>());
        when(projects.findById("proj-1")).thenReturn(Optional.of(p));

        assertThat(service.reportAutoMaskFailure("proj-1")).isEmpty();

        verify(reports, never()).save(any());
    }

    @Test
    void resolving_stamps_who_and_when_and_reopening_clears_it() {
        MaskReport r = MaskReport.builder().id("rep-1").project(project()).build();
        r.setIssueList(List.of(MaskReportIssue.MASK_NOT_GENERATED_PROPERLY));
        when(reports.findById("rep-1")).thenReturn(Optional.of(r));
        User admin = user();
        admin.setId("admin-1");
        admin.setName("Admin");
        when(users.findById("admin-1")).thenReturn(Optional.of(admin));

        service.updateStatus("admin-1", "rep-1", MaskReportStatus.RESOLVED, "model drift");
        assertThat(r.getStatus()).isEqualTo(MaskReportStatus.RESOLVED);
        assertThat(r.getResolvedAt()).isNotNull();
        assertThat(r.getResolvedBy().getName()).isEqualTo("Admin");
        assertThat(r.getAdminNote()).isEqualTo("model drift");

        service.updateStatus("admin-1", "rep-1", MaskReportStatus.NEW, null);
        // A live report showing "resolved by Admin" is a lie the queue must not tell.
        assertThat(r.getResolvedAt()).isNull();
        assertThat(r.getResolvedBy()).isNull();
        assertThat(r.getAdminNote()).isEqualTo("model drift");
    }
}
