package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.dto.*;
import com.gridstore.huevista.account.model.*;
import com.gridstore.huevista.account.repository.*;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final OrganizationRepository orgRepository;
    private final OrgMembershipRepository membershipRepository;
    private final DistributorRetailerLinkRepository linkRepository;
    private final RetailerBrandAssignmentRepository brandAssignmentRepository;
    private final RetailerFeatureAssignmentRepository featureAssignmentRepository;
    private final UserRepository userRepository;
    private final HouseDistributorService houseDistributorService;

    @Transactional
    public OrgResponse createOrganization(String userId, CreateOrgRequest request) {
        if (orgRepository.existsBySlug(request.getSlug())) {
            throw new IllegalArgumentException("Slug already taken: " + request.getSlug());
        }

        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Organization org = orgRepository.save(Organization.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .type(request.getType())
                .owner(owner)
                .build());

        membershipRepository.save(OrgMembership.builder()
                .user(owner)
                .organization(org)
                .role(OrgMemberRole.OWNER)
                .build());

        log.info("Organization created: id={} slug={} type={}", org.getId(), org.getSlug(), org.getType());
        return OrgResponse.from(org);
    }

    /**
     * Provision a RETAILER organization for a newly-signed-up shop owner: derives a
     * unique slug from the shop name and creates the org (+ OWNER membership). Reuses
     * an existing retailer org if the user already owns one. Called from trial signup.
     */
    @Transactional
    public Organization provisionRetailerOrg(String userId, String shopName, String city, String state) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        var existing = membershipRepository.findByUserId(userId).stream()
                .map(OrgMembership::getOrganization)
                .filter(o -> o.getType() == OrgType.RETAILER)
                .findFirst();
        if (existing.isPresent()) return existing.get();

        Organization org = orgRepository.save(Organization.builder()
                .name(shopName)
                .slug(uniqueSlug(shopName))
                .type(OrgType.RETAILER)
                .city(blankToNull(city))
                .state(blankToNull(state))
                .owner(owner)
                .build());

        membershipRepository.save(OrgMembership.builder()
                .user(owner)
                .organization(org)
                .role(OrgMemberRole.OWNER)
                .build());

        log.info("Retailer org provisioned at signup: id={} slug={}", org.getId(), org.getSlug());
        return org;
    }

    /**
     * Provision a DISTRIBUTOR organization for a newly-created distributor account.
     * Mirrors {@link #provisionRetailerOrg}: derives a unique slug, creates the org
     * (+ OWNER membership), and reuses an existing distributor org if the user
     * already owns one. Called from admin distributor creation.
     */
    @Transactional
    public Organization provisionDistributorOrg(String userId, String companyName, String city, String state) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        var existing = membershipRepository.findByUserId(userId).stream()
                .map(OrgMembership::getOrganization)
                .filter(o -> o.getType() == OrgType.DISTRIBUTOR)
                .findFirst();
        if (existing.isPresent()) return existing.get();

        Organization org = orgRepository.save(Organization.builder()
                .name(companyName)
                .slug(uniqueSlug(companyName))
                .type(OrgType.DISTRIBUTOR)
                .city(blankToNull(city))
                .state(blankToNull(state))
                .owner(owner)
                .build());

        membershipRepository.save(OrgMembership.builder()
                .user(owner)
                .organization(org)
                .role(OrgMemberRole.OWNER)
                .build());

        log.info("Distributor org provisioned: id={} slug={}", org.getId(), org.getSlug());
        return org;
    }

    private String uniqueSlug(String name) {
        String base = name == null ? "" : name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) base = "shop";
        String slug = base;
        int n = 2;
        while (orgRepository.existsBySlug(slug)) {
            slug = base + "-" + n++;
        }
        return slug;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @Transactional(readOnly = true)
    public OrgResponse getOrganization(String requestingUserId, String orgId) {
        Organization org = findOrg(orgId);
        requireMember(requestingUserId, orgId);
        return OrgResponse.from(org);
    }

    @Transactional(readOnly = true)
    public List<OrgResponse> getMyOrganizations(String userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> OrgResponse.from(m.getOrganization()))
                .toList();
    }

    @Transactional
    public MemberResponse addMember(String requestingUserId, String orgId, AddMemberRequest request) {
        Organization org = findOrg(orgId);
        requireOwner(requestingUserId, orgId);

        if (membershipRepository.findByUserIdAndOrganizationId(request.getUserId(), orgId).isPresent()) {
            throw new IllegalArgumentException("User is already a member of this organization");
        }

        User newMember = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getUserId()));

        OrgMembership membership = membershipRepository.save(OrgMembership.builder()
                .user(newMember)
                .organization(org)
                .role(request.getRole())
                .build());

        log.info("Member added: user={} org={} role={}", request.getUserId(), orgId, request.getRole());
        return MemberResponse.from(membership);
    }

    @Transactional
    public void removeMember(String requestingUserId, String orgId, String targetUserId) {
        requireOwner(requestingUserId, orgId);
        if (requestingUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("Owner cannot remove themselves");
        }
        membershipRepository.deleteByUserIdAndOrganizationId(targetUserId, orgId);
        log.info("Member removed: user={} org={}", targetUserId, orgId);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> getMembers(String requestingUserId, String orgId) {
        requireMember(requestingUserId, orgId);
        return membershipRepository.findByOrganizationId(orgId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    @Transactional
    public OrgResponse linkRetailer(String requestingUserId, String distributorOrgId, LinkRetailerRequest request) {
        Organization distributor = findOrg(distributorOrgId);

        if (distributor.getType() != OrgType.DISTRIBUTOR) {
            throw new IllegalArgumentException("Organization is not a distributor");
        }
        requireOwnerOrManager(requestingUserId, distributorOrgId);

        Organization retailer = orgRepository.findById(request.getRetailerOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Retailer org not found: " + request.getRetailerOrgId()));

        if (retailer.getType() != OrgType.RETAILER) {
            throw new IllegalArgumentException("Target organization is not a retailer");
        }

        // Consent: linking grants the distributor visibility into the retailer's data,
        // so the caller must also control the retailer org. Without this, any
        // distributor could link an arbitrary retailer by guessing its id.
        if (!membershipRepository.existsByUserIdAndOrganizationIdAndRole(
                requestingUserId, request.getRetailerOrgId(), OrgMemberRole.OWNER)) {
            throw new SecurityException("You can only link a retailer organization that you own");
        }

        // A shop has exactly one distributor (enforced by a unique index since V40), so
        // this either refuses or replaces — it can no longer just add a second row.
        // Taking a shop off the HOUSE distributor is the one replacement allowed: that
        // link is the platform's fallback rather than a relationship anybody agreed to,
        // and a distributor picking up a shop HueVista was carrying directly is exactly
        // what this endpoint is for. Taking one off another distributor is not the
        // caller's to do — that side has to let go first, or an admin re-files it.
        List<DistributorRetailerLink> existing = linkRepository.findByRetailerId(request.getRetailerOrgId());
        for (DistributorRetailerLink current : existing) {
            String currentId = current.getDistributor().getId();
            if (currentId.equals(distributorOrgId)) {
                throw new IllegalArgumentException("Retailer is already linked to this distributor");
            }
            if (!houseDistributorService.isHouse(current.getDistributor())) {
                throw new IllegalStateException(
                        "That shop is already with another distributor. They need to release it first, "
                                + "or an administrator can move it.");
            }
        }
        if (!existing.isEmpty()) {
            linkRepository.deleteAll(existing);
            linkRepository.flush();
        }

        linkRepository.save(DistributorRetailerLink.builder()
                .distributor(distributor)
                .retailer(retailer)
                .commissionRateOverride(request.getCommissionRateOverride())
                .build());

        log.info("Retailer linked: distributor={} retailer={}", distributorOrgId, request.getRetailerOrgId());
        return OrgResponse.from(retailer);
    }

    /**
     * End a distributor ↔ retailer relationship.
     *
     * There was previously no way out of a link at all: once a shop was attached to a
     * distributor it stayed attached forever, so a distributor could never drop a shop
     * and a shop could never move networks. Either side may end it — the distributor
     * (owner/manager of the distributor org) or the shop itself (its owner) — because a
     * relationship one party can't leave isn't one.
     *
     * The shop does NOT become distributor-less. It moves to the house distributor,
     * which is the same place a shop nobody else brought in is created. Leaving used to
     * delete the link and stop, which put the shop exactly where creation no longer
     * allows: outside every downline, a stray root in the network tree, with nobody
     * answerable for it. Ending a relationship is a change of distributor, not the
     * absence of one.
     *
     * The shop's brand AND page assignments go with the old link: both were granted BY
     * that distributor, so leaving must not silently leave the shop carrying brands
     * nobody is supplying, or locked out of pages nobody is administering. Both revert
     * to unrestricted rather than to zero, so ending a distributor relationship never
     * quietly empties a working catalogue or strands a shop with no usable app.
     */
    @Transactional
    public void unlinkRetailer(String requestingUserId, String distributorOrgId, String retailerOrgId) {
        DistributorRetailerLink link = linkRepository
                .findByDistributorIdAndRetailerId(distributorOrgId, retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That shop is not linked to this distributor."));

        boolean distributorSide = membershipRepository
                .existsByUserIdAndOrganizationIdAndRole(requestingUserId, distributorOrgId, OrgMemberRole.OWNER)
                || membershipRepository.existsByUserIdAndOrganizationIdAndRole(
                        requestingUserId, distributorOrgId, OrgMemberRole.MANAGER);
        boolean retailerSide = membershipRepository
                .existsByUserIdAndOrganizationIdAndRole(requestingUserId, retailerOrgId, OrgMemberRole.OWNER);
        if (!distributorSide && !retailerSide) {
            throw new SecurityException(
                    "Only the distributor or the shop itself can end this link.");
        }
        // Leaving the house distributor would have nowhere to go — it IS the fallback.
        if (houseDistributorService.isHouseOrgId(distributorOrgId)) {
            throw new IllegalStateException(
                    "This shop is with HueVista directly. Move it to a distributor instead of "
                            + "ending the link — every shop belongs to one.");
        }

        linkRepository.delete(link);
        linkRepository.flush();
        clearDistributorGrants(retailerOrgId);
        houseDistributorService.ensureHouseOrg().ifPresent(house ->
                linkRepository.save(DistributorRetailerLink.builder()
                        .distributor(house)
                        .retailer(orgRepository.getReferenceById(retailerOrgId))
                        .build()));

        log.info("Retailer unlinked from distributor={} and moved to the house distributor: retailer={} by={}",
                distributorOrgId, retailerOrgId, requestingUserId);
    }

    /**
     * Drop everything the outgoing distributor granted this shop, back to unrestricted.
     *
     * Shared by unlinking and by an admin re-filing a shop, because both leave the
     * grants pointing at a distributor who no longer supplies the shop — and a
     * restriction whose author is gone is one nobody can lift.
     */
    private void clearDistributorGrants(String retailerOrgId) {
        brandAssignmentRepository.deleteByRetailerId(retailerOrgId);
        featureAssignmentRepository.deleteByRetailerId(retailerOrgId);
        orgRepository.findById(retailerOrgId).ifPresent(retailer -> {
            retailer.setBrandsRestricted(false);
            retailer.setFeaturesRestricted(false);
            orgRepository.save(retailer);
        });
    }

    /**
     * ADMIN: move a shop to another distributor (or to the house one when blank).
     *
     * The distributor chosen when a shop is created was permanent: {@link
     * #linkRetailer} demands the caller own BOTH organizations, which an admin never
     * does, so a shop filed under the wrong distributor — or one that changed supplier —
     * could not be moved at all. Authorisation is the ADMIN role at the endpoint; the
     * consent check on linkRetailer exists to stop a distributor helping themselves to
     * someone else's shop, which is not what an admin re-filing one is.
     *
     * A no-op when the shop is already there, so pressing it twice does nothing rather
     * than clearing the shop's grants a second time.
     */
    @Transactional
    public OrgResponse moveRetailerToDistributor(String adminUserId, String retailerOrgId,
                                                 String distributorOrgId) {
        Organization retailer = orgRepository.findById(retailerOrgId)
                .filter(o -> o.getType() == OrgType.RETAILER)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + retailerOrgId));
        Organization target = (distributorOrgId == null || distributorOrgId.isBlank())
                ? houseDistributorService.ensureHouseOrg().orElseThrow(() -> new IllegalStateException(
                        "The house distributor could not be provisioned — the platform has no admin account."))
                : orgRepository.findById(distributorOrgId.trim())
                        .filter(o -> o.getType() == OrgType.DISTRIBUTOR)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Distributor not found: " + distributorOrgId));

        List<DistributorRetailerLink> existing = linkRepository.findByRetailerId(retailerOrgId);
        if (existing.size() == 1 && existing.get(0).getDistributor().getId().equals(target.getId())) {
            return OrgResponse.from(retailer);
        }

        linkRepository.deleteAll(existing);
        linkRepository.flush();
        // The grants belonged to the previous distributor; the new one starts from
        // "everything", and tightens it themselves if they want to.
        if (!existing.isEmpty()) {
            clearDistributorGrants(retailerOrgId);
        }
        linkRepository.save(DistributorRetailerLink.builder()
                .distributor(target)
                .retailer(retailer)
                .build());

        log.info("Admin {} moved shop {} to distributor {}", adminUserId, retailerOrgId, target.getName());
        return OrgResponse.from(retailer);
    }

    @Transactional(readOnly = true)
    public List<OrgResponse> getLinkedRetailers(String requestingUserId, String distributorOrgId) {
        requireMember(requestingUserId, distributorOrgId);
        return linkRepository.findByDistributorId(distributorOrgId).stream()
                .map(l -> OrgResponse.from(l.getRetailer()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrgResponse> getDistributorsForRetailer(String requestingUserId, String retailerOrgId) {
        requireMember(requestingUserId, retailerOrgId);
        return linkRepository.findByRetailerId(retailerOrgId).stream()
                .map(l -> OrgResponse.from(l.getDistributor()))
                .toList();
    }

    /**
     * The id of the first organization this user OWNS, or null. Used to refuse
     * self-service account deletion for an owner: the org's access codes, staff, kiosk
     * takings and payouts all hang off that account, so tombstoning it silently would
     * leave a shop running with nobody to bill or pay.
     */
    @Transactional(readOnly = true)
    public String firstOwnedOrgId(String userId) {
        return membershipRepository.findByUserId(userId).stream()
                .filter(m -> m.getRole() == OrgMemberRole.OWNER)
                .map(m -> m.getOrganization().getId())
                .findFirst()
                .orElse(null);
    }

    private Organization findOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    }

    /** Any membership role (OWNER/MANAGER/member) may read org-scoped data. Blocks the
     *  cross-tenant IDOR where any authenticated user could read another org by id. */
    private void requireMember(String userId, String orgId) {
        if (membershipRepository.findByUserIdAndOrganizationId(userId, orgId).isEmpty()) {
            throw new SecurityException("You are not a member of this organization");
        }
    }

    private void requireOwner(String userId, String orgId) {
        if (!membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER)) {
            throw new SecurityException("Only the organization owner can perform this action");
        }
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean owner = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER);
        boolean manager = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!owner && !manager) {
            throw new SecurityException("Only owners or managers can perform this action");
        }
    }
}
