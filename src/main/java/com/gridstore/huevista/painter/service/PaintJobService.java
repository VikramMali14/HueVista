package com.gridstore.huevista.painter.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.painter.dto.AcceptPaintJobRequest;
import com.gridstore.huevista.painter.dto.CreatePaintJobRequest;
import com.gridstore.huevista.painter.dto.DeclinePaintJobRequest;
import com.gridstore.huevista.painter.dto.PaintJobResponse;
import com.gridstore.huevista.painter.model.PaintJob;
import com.gridstore.huevista.painter.model.PaintJobStatus;
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.model.PainterProfile;
import com.gridstore.huevista.painter.repository.PaintJobRepository;
import com.gridstore.huevista.painter.repository.PainterProfileRepository;
import com.gridstore.huevista.painter.repository.PainterRetailerLinkRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaintJobService {

    private final PaintJobRepository jobRepository;
    private final PainterRetailerLinkRepository linkRepository;
    private final PainterProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final com.gridstore.huevista.account.repository.OrgMembershipRepository orgMembershipRepository;
    private final com.gridstore.huevista.account.repository.CustomerEntitlementRepository entitlementRepository;
    private final com.gridstore.huevista.notification.EmailSender emailSender;
    /** The painter's "open my jobs" link has to be the website, not this API — see SiteUrls. */
    private final com.gridstore.huevista.common.web.SiteUrls siteUrls;

    @Transactional
    public PaintJobResponse createJob(String requesterUserId, CreatePaintJobRequest req) {
        Organization retailer = organizationRepository.findById(req.getRetailerId())
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + req.getRetailerId()));

        requireOwnerOrManager(requesterUserId, retailer.getId());

        Project project = projectRepository.findById(req.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + req.getProjectId()));

        // The project must actually belong to this shop's world. Without this any retailer
        // owner could pass an arbitrary project UUID and create a job on a stranger's room:
        // it exposed that project (and its customer's identity and site address) to them
        // and to their painter, and pushed a job into an unrelated customer's list from a
        // shop they had never visited.
        assertProjectBelongsToRetailer(project, retailer.getId());

        jobRepository.findByProjectId(project.getId()).ifPresent(j -> {
            throw new IllegalArgumentException(
                    "Project " + project.getId() + " already has a job (id=" + j.getId() + ").");
        });

        // customer_id is NOT NULL, and a guest project (created against an access code,
        // never claimed by an account) has no user — building a job from one used to blow
        // up on the constraint, and every later read of job.getCustomer() would NPE into a
        // 500. Refuse it with an explanation instead.
        if (project.getUser() == null) {
            throw new IllegalArgumentException(
                    "This room was created by a walk-in guest who hasn't made an account yet, so "
                    + "there's nobody to assign the job to. Ask them to sign up (their room "
                    + "carries over), then create the job.");
        }

        User painter = userRepository.findById(req.getPainterId())
                .orElseThrow(() -> new ResourceNotFoundException("Painter user not found: " + req.getPainterId()));
        if (painter.getRole() != UserRole.PAINTER) {
            throw new IllegalArgumentException("Assigned user is not a PAINTER: " + painter.getId());
        }
        if (!linkRepository.existsByPainterIdAndRetailerIdAndStatus(
                painter.getId(), retailer.getId(), PainterLinkStatus.ACTIVE)) {
            throw new IllegalArgumentException(
                    "Painter " + painter.getId() + " is not actively linked to retailer " + retailer.getId());
        }

        PaintJob job = PaintJob.builder()
                .project(project)
                .retailer(retailer)
                .customer(project.getUser())
                .painter(painter)
                .status(PaintJobStatus.NEW)
                .siteAddress(req.getSiteAddress())
                .estimatedAreaSqft(req.getEstimatedAreaSqft())
                .estimatedPaintLiters(req.getEstimatedPaintLiters())
                .notes(req.getNotes())
                .build();
        job = jobRepository.save(job);
        notifyPainterOfNewJob(job, retailer, painter);
        log.info("Created paint job {} for project {} → painter {}", job.getId(), project.getId(), painter.getId());
        return PaintJobResponse.from(job);
    }

    /**
     * Tell the painter a job is waiting for them.
     *
     * <p>Nothing else does. A job is created by the shop and lands in a list the painter
     * has to already be looking at, so until this mail the feature relied on the painter
     * happening to open the app — and a NEW job nobody opens is indistinguishable from no
     * job at all. The shop, meanwhile, sees it assigned and assumes it was received.
     *
     * <p>Best-effort: the job is already saved, and a mail outage must not roll back an
     * assignment the shop has been told succeeded.
     */
    private void notifyPainterOfNewJob(PaintJob job, Organization retailer, User painter) {
        try {
            if (painter.getEmail() == null || painter.getEmail().isBlank()
                    || com.gridstore.huevista.auth.util.Emails.isSynthetic(painter)) {
                return;
            }
            StringBuilder body = new StringBuilder()
                    .append("Hi ").append(firstName(painter)).append(",\n\n")
                    .append(retailer.getName()).append(" has assigned you a painting job.\n\n");
            if (job.getSiteAddress() != null && !job.getSiteAddress().isBlank()) {
                body.append("Site:      ").append(job.getSiteAddress()).append('\n');
            }
            if (job.getEstimatedAreaSqft() != null) {
                body.append("Area:      ").append(job.getEstimatedAreaSqft()).append(" sq ft\n");
            }
            if (job.getEstimatedPaintLiters() != null) {
                body.append("Paint:     ").append(job.getEstimatedPaintLiters()).append(" litres (estimated)\n");
            }
            if (job.getNotes() != null && !job.getNotes().isBlank()) {
                body.append("\nNotes:\n").append(job.getNotes()).append('\n');
            }
            // Deliberately the dashboard and not a jobs page: the painter-facing job UI
            // does not exist in the web app yet (the accept/decline endpoints are API-only,
            // see PaintJobController), and a mail that sends someone to a 404 is worse than
            // one that sends them nowhere. Point at the door they can actually open, and let
            // the shop be the fallback. Repoint this at the jobs page when it ships.
            body.append("\nSign in to HueVista:\n")
                    .append(siteUrls.on("/dashboard")).append("\n\n")
                    .append(retailer.getName())
                    .append(" is waiting on your answer — get in touch with them to accept it "
                            + "or turn it down.\n\n")
                    .append("— HueVista");
            emailSender.send(painter.getEmail(),
                    "New painting job from " + retailer.getName(),
                    body.toString());
        } catch (Exception e) {
            log.warn("New-job email for job {} failed: {}", job.getId(), e.getMessage());
        }
    }

    private static String firstName(User user) {
        String name = user != null ? user.getName() : null;
        if (name == null || name.isBlank()) return "there";
        return name.strip().split("\\s+")[0];
    }

    @Transactional(readOnly = true)
    public PaintJobResponse getJob(String requesterUserId, String jobId) {
        PaintJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        assertVisible(requesterUserId, job);
        return PaintJobResponse.from(job);
    }

    /** Hard cap on page sizes — bounds memory/serialization, newest jobs win. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Clamps page/size (page >= 0, 1 <= size <= 200) instead of rejecting out-of-range values. */
    private static org.springframework.data.domain.Pageable pageOf(int page, int size) {
        return org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }

    @Transactional(readOnly = true)
    public List<PaintJobResponse> listForPainter(String painterUserId, int page, int size) {
        return jobRepository.findForPainterWithDetails(painterUserId, pageOf(page, size))
                .stream().map(PaintJobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PaintJobResponse> listForRetailer(String requesterUserId, String retailerOrgId, int page, int size) {
        Organization retailer = organizationRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + retailerOrgId));
        requireOwnerOrManager(requesterUserId, retailerOrgId);
        return jobRepository.findForRetailerWithDetails(retailerOrgId, pageOf(page, size))
                .stream().map(PaintJobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PaintJobResponse> listForCustomer(String customerUserId, int page, int size) {
        return jobRepository.findForCustomerWithDetails(customerUserId, pageOf(page, size))
                .stream().map(PaintJobResponse::from).toList();
    }

    @Transactional
    public PaintJobResponse accept(String painterUserId, String jobId, AcceptPaintJobRequest req) {
        PaintJob job = requireOwnedByPainter(painterUserId, jobId);
        if (job.getStatus() != PaintJobStatus.NEW) {
            throw new IllegalStateException("Only NEW jobs can be accepted (current: " + job.getStatus() + ").");
        }
        job.setStatus(PaintJobStatus.ACCEPTED);
        job.setQuotedAmountInr(req.getQuotedAmountInr());
        job.setEstimatedDays(req.getEstimatedDays());
        if (req.getScheduledFor() != null) job.setScheduledFor(req.getScheduledFor());
        return PaintJobResponse.from(job);
    }

    @Transactional
    public PaintJobResponse decline(String painterUserId, String jobId, DeclinePaintJobRequest req) {
        PaintJob job = requireOwnedByPainter(painterUserId, jobId);
        if (job.getStatus() != PaintJobStatus.NEW) {
            throw new IllegalStateException("Only NEW jobs can be declined (current: " + job.getStatus() + ").");
        }
        job.setStatus(PaintJobStatus.DECLINED);
        job.setDeclineReason(req.getReason());
        return PaintJobResponse.from(job);
    }

    @Transactional
    public PaintJobResponse markInProgress(String painterUserId, String jobId) {
        PaintJob job = requireOwnedByPainter(painterUserId, jobId);
        if (job.getStatus() != PaintJobStatus.ACCEPTED) {
            throw new IllegalStateException("Only ACCEPTED jobs can be started (current: " + job.getStatus() + ").");
        }
        job.setStatus(PaintJobStatus.IN_PROGRESS);
        job.setStartedAt(LocalDateTime.now());
        return PaintJobResponse.from(job);
    }

    @Transactional
    public PaintJobResponse markCompleted(String painterUserId, String jobId) {
        PaintJob job = requireOwnedByPainter(painterUserId, jobId);
        if (job.getStatus() != PaintJobStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Only IN_PROGRESS jobs can be completed (current: " + job.getStatus() + ").");
        }
        job.setStatus(PaintJobStatus.COMPLETED);
        job.setCompletedAt(LocalDateTime.now());

        // Increment painter's jobsCompleted counter for sorting / reputation
        profileRepository.findByUserId(painterUserId).ifPresent(profile -> {
            profile.setJobsCompleted(profile.getJobsCompleted() + 1);
            profileRepository.save(profile);
        });
        return PaintJobResponse.from(job);
    }

    @Transactional
    public PaintJobResponse cancel(String requesterUserId, String jobId, String reason) {
        PaintJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        boolean isRetailerOwner = job.getRetailer().getOwner().getId().equals(requesterUserId);
        boolean isCustomer = job.getCustomer() != null && job.getCustomer().getId().equals(requesterUserId);
        if (!isRetailerOwner && !isCustomer) {
            throw new SecurityException("Only the retailer owner or the customer may cancel this job.");
        }
        if (EnumSet.of(PaintJobStatus.COMPLETED, PaintJobStatus.CANCELLED).contains(job.getStatus())) {
            throw new IllegalStateException("Job is already finalised: " + job.getStatus());
        }
        job.setStatus(PaintJobStatus.CANCELLED);
        job.setDeclineReason(reason);
        return PaintJobResponse.from(job);
    }

    // ── helpers ──

    private PaintJob requireOwnedByPainter(String painterUserId, String jobId) {
        PaintJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        if (job.getPainter() == null || !job.getPainter().getId().equals(painterUserId)) {
            throw new SecurityException("This job is not assigned to the requesting painter.");
        }
        return job;
    }

    private void assertVisible(String requesterUserId, PaintJob job) {
        boolean isPainter  = job.getPainter() != null && job.getPainter().getId().equals(requesterUserId);
        boolean isCustomer = job.getCustomer() != null && job.getCustomer().getId().equals(requesterUserId);
        boolean isRetailer = job.getRetailer().getOwner().getId().equals(requesterUserId);
        if (!isPainter && !isCustomer && !isRetailer) {
            throw new SecurityException("Job " + job.getId() + " is not visible to user " + requesterUserId);
        }
    }

    /**
     * A shop may only route its OWN work: the project must belong to the shop itself
     * (a retailer's own room) or to a customer who came in through one of the shop's
     * access codes. Anything else is another shop's — or another person's — project.
     */
    private void assertProjectBelongsToRetailer(Project project, String retailerOrgId) {
        // Came in on one of this shop's access codes (guest or redeemed customer).
        if (project.getAccessCode() != null
                && project.getAccessCode().getOrganization() != null
                && retailerOrgId.equals(project.getAccessCode().getOrganization().getId())) {
            return;
        }
        String projectUserId = project.getUser() != null ? project.getUser().getId() : null;
        if (projectUserId != null) {
            // The shop's own room: the owner's, or any member of the shop's.
            if (orgMembershipRepository.findByUserIdAndOrganizationId(projectUserId, retailerOrgId).isPresent()) {
                return;
            }
            // Or a customer this shop onboarded — the entitlement's "managed by" link,
            // which is what a walk-in gets when they redeem the shop's code.
            boolean managedByThisShop = entitlementRepository.findByCustomerId(projectUserId)
                    .map(e -> e.getRetailerOrg() != null
                            && retailerOrgId.equals(e.getRetailerOrg().getId()))
                    .orElse(false);
            if (managedByThisShop) {
                return;
            }
        }
        throw new SecurityException(
                "That room doesn't belong to your shop — you can only create jobs for your own "
                + "rooms or for customers who used one of your access codes.");
    }

    /**
     * Owner OR manager, matching every other shop tool.
     *
     * Painter management alone tested {@code retailer.getOwner()} directly, so a shop
     * MANAGER — who can issue customer access codes, grant projects and run the portal —
     * could not invite a painter or see the shop's jobs. One role check, one answer.
     */
    private void requireOwnerOrManager(String userId, String retailerOrgId) {
        // The org's own owner field OR a membership row. Both, because they are two
        // records of the same fact and they can disagree: every org provisioned through
        // AccountService gets an OWNER membership, but one created directly carries only
        // the owner pointer. Checking membership alone would lock the actual owner out of
        // their own shop.
        boolean ok = organizationRepository.findById(retailerOrgId)
                        .map(o -> o.getOwner() != null && o.getOwner().getId().equals(userId))
                        .orElse(false)
                || orgMembershipRepository.existsByUserIdAndOrganizationIdAndRole(
                        userId, retailerOrgId, OrgMemberRole.OWNER)
                || orgMembershipRepository.existsByUserIdAndOrganizationIdAndRole(
                        userId, retailerOrgId, OrgMemberRole.MANAGER);
        if (!ok) {
            throw new SecurityException("Only the shop owner or a manager can do that.");
        }
    }
}
