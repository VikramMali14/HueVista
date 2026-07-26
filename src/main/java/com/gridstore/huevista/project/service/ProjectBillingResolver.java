package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Answers one question for every AI run: <em>whose subscription pays for this?</em>
 *
 * Three kinds of project exist and they used to be billed by three different ad-hoc
 * rules, which is how a paying customer ended up unable to run anything at all:
 *
 * <ul>
 *   <li><b>Shop's own project</b> — no access code. The retailer pays, from their own
 *       monthly quota.</li>
 *   <li><b>Walk-in guest project</b> — owned by an access code, no user. The issuing
 *       shop pays.</li>
 *   <li><b>Redeemed customer's project</b> — owned by a CUSTOMER account <em>and</em>
 *       linked to the access code they redeemed. The issuing shop pays here too: the
 *       shop already reserved an image credit per assigned project when it generated
 *       the code, and a CUSTOMER can never hold a subscription of their own (buying one
 *       is explicitly refused in BillingService#createSubscription). Billing this to the
 *       customer meant every such run died on "No active subscription. Subscribe to use
 *       AI features." — a plan they are forbidden from buying — while the shop had
 *       already paid for the work.</li>
 * </ul>
 *
 * Whenever a code is involved the run should spend the credit that code is HOLDING
 * rather than charge a fresh one, so {@link Target#accessCodeId()} is carried through
 * to the charge site (see SegmentationService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBillingResolver {

    private final ProjectRepository projectRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final OrgMembershipRepository orgMembershipRepository;

    /**
     * @param billedUserId the account whose subscription pays
     * @param accessCodeId non-null when this run is covered by an access code, and should
     *                     therefore spend that code's held credit before charging a new one
     */
    public record Target(String billedUserId, String accessCodeId) {
        public boolean coveredByCode() {
            return accessCodeId != null;
        }
    }

    /**
     * Resolve the paying account for a project. Empty when nothing can be billed (an
     * orphaned project, or a shop whose owner account is gone) — callers treat that as
     * "cannot run AI" rather than silently doing free work.
     */
    @Transactional(readOnly = true)
    public Optional<Target> resolve(String projectId) {
        String accessCodeId = projectRepository.findAccessCodeIdById(projectId).orElse(null);
        if (accessCodeId != null) {
            return shopOwnerOf(accessCodeId).map(ownerId -> new Target(ownerId, accessCodeId));
        }
        return projectRepository.findUserIdById(projectId).map(userId -> new Target(userId, null));
    }

    /** The OWNER account of the organization that issued a code — the account billed for it. */
    @Transactional(readOnly = true)
    public Optional<String> shopOwnerOf(String accessCodeId) {
        return accessCodeRepository.findOrganizationIdById(accessCodeId)
                .flatMap(orgId -> orgMembershipRepository
                        .findUserIdsByOrganizationIdAndRole(orgId, OrgMemberRole.OWNER)
                        .stream().findFirst());
    }
}
