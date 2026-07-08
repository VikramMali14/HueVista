package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.paint.dto.CreateRetailerComboRequest;
import com.gridstore.huevista.paint.dto.RetailerComboResponse;
import com.gridstore.huevista.paint.model.RetailerCombo;
import com.gridstore.huevista.paint.repository.RetailerComboRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Retailer-curated three-shade combinations ("shop picks"). The retailer manages
 * them from the portal; the studio's AI Suggest tab shows them to everyone who
 * visualises under that shop — the retailer's own staff, customers holding a
 * valid entitlement from the shop, and anonymous guests on a shop access code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetailerComboService {

    /** Enough for a themed wall of suggestions; stops unbounded rows per org. */
    private static final int MAX_COMBOS_PER_ORG = 24;

    private final RetailerComboRepository comboRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMembershipRepository membershipRepository;
    private final CustomerEntitlementRepository entitlementRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;

    // --- Retailer-side management (portal) ---

    @Transactional(readOnly = true)
    public List<RetailerComboResponse> list(String userId, String orgId) {
        requireMember(userId, orgId);
        return comboRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId)
                .stream().map(RetailerComboResponse::from).toList();
    }

    @Transactional
    public RetailerComboResponse create(String userId, String orgId, CreateRetailerComboRequest req) {
        requireOwnerOrManager(userId, orgId);
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        if (comboRepository.countByOrganizationId(orgId) >= MAX_COMBOS_PER_ORG) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have " + MAX_COMBOS_PER_ORG + " combinations. Remove one to add another.");
        }

        List<CreateRetailerComboRequest.ComboShadeDto> shades = req.getShades();
        RetailerCombo combo = comboRepository.save(RetailerCombo.builder()
                .organization(org)
                .name(req.getName().trim())
                .scope(req.getScope())
                .shade1Code(shades.get(0).getCode().trim())
                .shade1Name(shades.get(0).getName().trim())
                .shade1Hex(normalizeHex(shades.get(0).getHex()))
                .shade2Code(shades.get(1).getCode().trim())
                .shade2Name(shades.get(1).getName().trim())
                .shade2Hex(normalizeHex(shades.get(1).getHex()))
                .shade3Code(shades.get(2).getCode().trim())
                .shade3Name(shades.get(2).getName().trim())
                .shade3Hex(normalizeHex(shades.get(2).getHex()))
                .build());
        log.info("Retailer combo created: org={} combo={} scope={} name={}",
                orgId, combo.getId(), combo.getScope(), combo.getName());
        return RetailerComboResponse.from(combo);
    }

    @Transactional
    public void delete(String userId, String orgId, String comboId) {
        requireOwnerOrManager(userId, orgId);
        RetailerCombo combo = comboRepository.findByIdAndOrganizationId(comboId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Combination not found: " + comboId));
        comboRepository.delete(combo);
        log.info("Retailer combo deleted: org={} combo={}", orgId, comboId);
    }

    // --- Studio-side read (retailer staff, customers, guests) ---

    /**
     * The combos the calling principal should see in the studio, resolved by who
     * they are. An empty list (never an error) when there is no shop to show —
     * the AI Suggest tab simply hides the section.
     */
    @Transactional(readOnly = true)
    public List<RetailerComboResponse> forPrincipal(String principalName, boolean guest) {
        String orgId = guest ? orgForGuest(principalName) : orgForUser(principalName);
        if (orgId == null) return List.of();
        return comboRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId)
                .stream().map(RetailerComboResponse::from).toList();
    }

    /** Guest principal = the redeemed access code's id; its shop owns the combos. */
    private String orgForGuest(String accessCodeId) {
        return accessCodeRepository.findById(accessCodeId)
                .filter(code -> !code.isExpired())
                .map(code -> code.getOrganization().getId())
                .orElse(null);
    }

    /**
     * A user sees their own shop's combos when they belong to a retailer org;
     * otherwise (CUSTOMER) the combos of the retailer whose valid entitlement
     * they hold. Expired entitlements show nothing — matching the project lock.
     */
    private String orgForUser(String userId) {
        String memberOrg = membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization())
                .filter(o -> o.getType() == OrgType.RETAILER)
                .map(Organization::getId)
                .findFirst()
                .orElse(null);
        if (memberOrg != null) return memberOrg;
        return entitlementRepository.findByCustomerId(userId)
                .filter(ent -> !ent.isExpired() && ent.getRetailerOrg() != null)
                .map(ent -> ent.getRetailerOrg().getId())
                .orElse(null);
    }

    // --- helpers ---

    private static String normalizeHex(String hex) {
        return hex.trim().toLowerCase(Locale.ROOT);
    }

    private void requireMember(String userId, String orgId) {
        if (membershipRepository.findByUserIdAndOrganizationId(userId, orgId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to this organization.");
        }
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean ok = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER)
                || membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the shop owner or a manager can manage suggested combinations.");
        }
    }
}
