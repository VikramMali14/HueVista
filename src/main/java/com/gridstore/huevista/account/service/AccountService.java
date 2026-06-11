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
    private final UserRepository userRepository;

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

        if (linkRepository.existsByDistributorIdAndRetailerId(distributorOrgId, request.getRetailerOrgId())) {
            throw new IllegalArgumentException("Retailer is already linked to this distributor");
        }

        linkRepository.save(DistributorRetailerLink.builder()
                .distributor(distributor)
                .retailer(retailer)
                .commissionRateOverride(request.getCommissionRateOverride())
                .build());

        log.info("Retailer linked: distributor={} retailer={}", distributorOrgId, request.getRetailerOrgId());
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
