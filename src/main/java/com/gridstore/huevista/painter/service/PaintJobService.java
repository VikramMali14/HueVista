package com.gridstore.huevista.painter.service;

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

    @Transactional
    public PaintJobResponse createJob(String requesterUserId, CreatePaintJobRequest req) {
        Organization retailer = organizationRepository.findById(req.getRetailerId())
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + req.getRetailerId()));

        if (!retailer.getOwner().getId().equals(requesterUserId)) {
            throw new SecurityException("Only the retailer owner may create paint jobs.");
        }

        Project project = projectRepository.findById(req.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + req.getProjectId()));

        jobRepository.findByProjectId(project.getId()).ifPresent(j -> {
            throw new IllegalArgumentException(
                    "Project " + project.getId() + " already has a job (id=" + j.getId() + ").");
        });

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
        log.info("Created paint job {} for project {} → painter {}", job.getId(), project.getId(), painter.getId());
        return PaintJobResponse.from(job);
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
        if (!retailer.getOwner().getId().equals(requesterUserId)) {
            throw new SecurityException("Only the retailer owner may list jobs for this retailer.");
        }
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
        boolean isCustomer = job.getCustomer().getId().equals(requesterUserId);
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
        boolean isCustomer = job.getCustomer().getId().equals(requesterUserId);
        boolean isRetailer = job.getRetailer().getOwner().getId().equals(requesterUserId);
        if (!isPainter && !isCustomer && !isRetailer) {
            throw new SecurityException("Job " + job.getId() + " is not visible to user " + requesterUserId);
        }
    }
}
