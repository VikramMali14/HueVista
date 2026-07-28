package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.ProjectGrant;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.ProjectGrantRepository;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.exception.SubscriptionRequiredException;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Giving projects away, and taking back the ones nobody used.
 *
 * Every project a shop hands out costs it an image credit — that was already true when a
 * shop ISSUED a code, and is now true when it grants projects to a customer or tops up a
 * code they already hold. Granting used to be a bare {@code allowance + 1} that reserved
 * nothing, so a shop could give away unlimited projects while its subscription showed no
 * change at all.
 *
 * <h2>What can be taken back</h2>
 * Only what has not been used. A project the customer has already created is spent — the
 * shop paid for the image and the work exists — so revoking reduces the ALLOWANCE, never
 * below what has actually been created.
 *
 * <h2>And only within the period that paid</h2>
 * Images reserved in one billing period cannot be released into the next: that would hand
 * the new period a credit the old one paid for, minting quota once per renewal. So a
 * grant is revocable only while the subscription that funded it is still in the same
 * period. After a renewal it is spent, used or not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGrantService {

    private final ProjectGrantRepository grantRepository;
    private final CustomerEntitlementRepository entitlementRepository;
    private final CustomerAccessCodeRepository codeRepository;
    private final OrgMembershipRepository membershipRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;
    private final BillingService billingService;
    private final com.gridstore.huevista.common.audit.AuditService auditService;

    /** Reserve the images and record the grant. Returns the ledger row. */
    @Transactional
    public ProjectGrant grantToCustomer(String requestingUserId, String orgId,
                                        String customerUserId, int projects) {
        requireOwnerOrManager(requestingUserId, orgId);
        requirePositive(projects);

        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer entitlement not found"));
        // "Managed by" is the shop the customer most recently redeemed with, and that
        // pointer moves. A shop that issued a code this customer is holding still has a
        // real relationship with them — refusing it here meant a customer who redeemed a
        // second shop's code could no longer be given projects by the first, which had
        // already paid for theirs.
        if (!entitlementRepository.isManagedBy(customerUserId, orgId)) {
            throw new SecurityException("This customer is not managed by your organization.");
        }

        Subscription sub = reserve(orgId, projects);
        ent.setProjectAllowance(ent.getProjectAllowance() + projects);
        entitlementRepository.save(ent);

        ProjectGrant grant = grantRepository.save(ProjectGrant.builder()
                .retailerOrgId(orgId)
                .customerUserId(customerUserId)
                .projects(projects)
                .subscriptionId(sub.getId())
                .periodStart(sub.getCurrentPeriodStart())
                .build());

        auditService.record(requestingUserId, "PROJECT_GRANT", "CUSTOMER", customerUserId,
                "org=" + orgId + " projects=" + projects + " allowance=" + ent.getProjectAllowance());
        log.info("Shop {} granted {} project(s) to customer {} (allowance now {})",
                orgId, projects, customerUserId, ent.getProjectAllowance());
        return grant;
    }

    /** Record a top-up onto a code. The caller has already reserved and applied it. */
    @Transactional
    public ProjectGrant recordCodeGrant(String orgId, String accessCodeId, int projects,
                                        Subscription fundedBy) {
        return grantRepository.save(ProjectGrant.builder()
                .retailerOrgId(orgId)
                .accessCodeId(accessCodeId)
                .projects(projects)
                .subscriptionId(fundedBy != null ? fundedBy.getId() : null)
                .periodStart(fundedBy != null ? fundedBy.getCurrentPeriodStart() : null)
                .build());
    }

    /**
     * Take a grant back and return its images to the shop's quota.
     *
     * Refuses on three separate grounds, each with its own message, because they mean
     * quite different things to the person clicking: already taken back, the customer has
     * used them, and the billing period that paid has closed.
     */
    @Transactional
    public ProjectGrant revoke(String requestingUserId, String orgId, String grantId) {
        ProjectGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Grant not found: " + grantId));
        if (!grant.getRetailerOrgId().equals(orgId)) {
            throw new SecurityException("That grant belongs to another shop.");
        }
        requireOwnerOrManager(requestingUserId, orgId);

        if (grant.isRevoked()) {
            throw new IllegalStateException("You've already taken this grant back.");
        }

        Subscription current = billingService.findEntitlingSubscription(ownerOf(orgId))
                .orElseThrow(() -> new SubscriptionRequiredException(
                        "Your subscription has ended, so there's no quota to return these projects to. "
                        + "Renew your plan first."));
        if (!grant.fundedBy(current.getId(), current.getCurrentPeriodStart())) {
            throw new IllegalStateException(
                    "This was granted in an earlier billing period, so it can't be taken back — "
                    + "those images came out of that period's quota, not this one's.");
        }

        int unused = unusedProjects(grant);
        if (unused < grant.getProjects()) {
            throw new IllegalStateException(
                    unused == 0
                            ? "The customer has already used these projects, so they're counted."
                            : "The customer has already used some of these projects — only "
                              + unused + " of " + grant.getProjects() + " are unused, and a grant "
                              + "comes back whole or not at all.");
        }

        // Compare-and-set: two clicks on the same row must not release the images twice.
        if (grantRepository.revokeIfLive(grantId, requestingUserId, LocalDateTime.now()) == 0) {
            throw new IllegalStateException("You've already taken this grant back.");
        }

        applyRevoke(grant);
        billingService.releaseReservedImages(ownerOf(orgId), grant.getProjects());

        auditService.record(requestingUserId, "PROJECT_GRANT_REVOKED", "GRANT", grantId,
                "org=" + orgId + " projects=" + grant.getProjects());
        log.info("Shop {} took back a grant of {} project(s) (grant={})",
                orgId, grant.getProjects(), grantId);
        return grantRepository.findById(grantId).orElse(grant);
    }

    /** Whether this grant could be taken back right now — drives the shop's row action. */
    @Transactional(readOnly = true)
    public boolean isRevocable(ProjectGrant grant) {
        if (grant.isRevoked()) return false;
        if (unusedProjects(grant) < grant.getProjects()) return false;
        return billingService.findEntitlingSubscription(ownerOf(grant.getRetailerOrgId()))
                .map(sub -> grant.fundedBy(sub.getId(), sub.getCurrentPeriodStart()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public java.util.List<ProjectGrant> listForOrg(String requestingUserId, String orgId) {
        requireOwnerOrManager(requestingUserId, orgId);
        return grantRepository.findByRetailerOrgIdOrderByCreatedAtDesc(orgId);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * How much of this grant is still unspent.
     *
     * A grant is not tracked per project, so "unused" is measured against the target as a
     * whole: what is left of the customer's allowance, or of the code's quota. That is the
     * honest reading — the projects a grant added are indistinguishable from the ones that
     * were already there, and the shop is asking "is there room to take some back".
     */
    private int unusedProjects(ProjectGrant grant) {
        if (grant.getCustomerUserId() != null) {
            return entitlementRepository.findByCustomerId(grant.getCustomerUserId())
                    .map(CustomerEntitlement::getProjectsRemaining)
                    .orElse(0);
        }
        return codeRepository.findById(grant.getAccessCodeId())
                .map(code -> Math.max(0, code.getProjectQuota()
                        - (int) projectRepository.countByAccessCodeId(code.getId())))
                .orElse(0);
    }

    /** Undo the grant on whichever target it topped up. */
    private void applyRevoke(ProjectGrant grant) {
        if (grant.getCustomerUserId() != null) {
            entitlementRepository.findByCustomerId(grant.getCustomerUserId()).ifPresent(ent -> {
                // Never below what has actually been created — those projects exist.
                int floor = ent.getProjectsCreated();
                ent.setProjectAllowance(Math.max(floor, ent.getProjectAllowance() - grant.getProjects()));
                entitlementRepository.save(ent);
            });
            return;
        }
        codeRepository.findById(grant.getAccessCodeId()).ifPresent(code -> {
            int created = (int) projectRepository.countByAccessCodeId(code.getId());
            code.setProjectQuota(Math.max(created, code.getProjectQuota() - grant.getProjects()));
            code.setReservedImages(Math.max(0, code.getReservedImages() - grant.getProjects()));
            codeRepository.save(code);
        });
    }

    /**
     * Reserve {@code projects} images against the shop owner's live plan — the same
     * all-or-nothing reservation code issuance makes. Without a plan there is no quota to
     * draw on, so the grant is refused rather than silently free.
     */
    private Subscription reserve(String orgId, int projects) {
        String ownerId = ownerOf(orgId);
        Subscription sub = subscriptionRepository
                .findEntitling(ownerId, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
                        LocalDateTime.now())
                .stream().findFirst()
                .orElseThrow(() -> new SubscriptionRequiredException(
                        "Your subscription has ended, so there's no image quota to draw from. "
                        + "Renew your plan to give a customer more projects."));
        if (subscriptionRepository.reserveImagesIfWithinLimit(sub.getId(), projects) == 0) {
            throw new QuotaExceededException(
                    "Not enough image quota to give " + projects + " project"
                    + (projects == 1 ? "" : "s") + ". Buy more images or give fewer.");
        }
        return sub;
    }

    private String ownerOf(String orgId) {
        return membershipRepository.findUserIdsByOrganizationIdAndRole(orgId, OrgMemberRole.OWNER)
                .stream().findFirst()
                .orElseThrow(() -> new QuotaExceededException(
                        "This shop has no owner account to bill. Contact support."));
    }

    private static void requirePositive(int projects) {
        if (projects < 1 || projects > 20) {
            throw new IllegalArgumentException("Give between 1 and 20 projects at a time.");
        }
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean ok = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER)
                || membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!ok) {
            throw new SecurityException("Only org owners or managers can manage customer projects.");
        }
    }
}
