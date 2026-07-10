package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.paint.dto.ShadeCodeSchemeResponse;
import com.gridstore.huevista.paint.dto.UpdateShadeCodeSchemeRequest;
import com.gridstore.huevista.paint.model.ShadeCodeScheme;
import com.gridstore.huevista.paint.repository.ShadeCodeSchemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * The shop's shade-code scheme: one stored pattern (prefix / inserted pair /
 * suffix) instead of a custom code per shade. The retailer manages it from the
 * portal; the studio reads it through {@code /api/me/shade-code-scheme} for
 * whoever is visualising under the shop — retailer staff, entitled customers,
 * and guests — and displays every shade code encoded with it.
 *
 * Shop resolution deliberately mirrors {@link RetailerComboService}: the two
 * travel together (both are "what this shop shows in the studio").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadeCodeSchemeService {

    private final ShadeCodeSchemeRepository schemeRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMembershipRepository membershipRepository;
    private final CustomerEntitlementRepository entitlementRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;

    // --- Retailer-side management (portal) ---

    @Transactional(readOnly = true)
    public ShadeCodeSchemeResponse get(String userId, String orgId) {
        requireMember(userId, orgId);
        return schemeRepository.findByOrganizationId(orgId)
                .map(ShadeCodeSchemeResponse::from)
                .orElseGet(ShadeCodeSchemeResponse::empty);
    }

    @Transactional
    public ShadeCodeSchemeResponse update(String userId, String orgId, UpdateShadeCodeSchemeRequest req) {
        requireOwnerOrManager(userId, orgId);
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));

        String prefix = normalize(req.getPrefix());
        String infix = normalize(req.getInfix());
        String suffix = normalize(req.getSuffix());

        // All three parts empty = the shop wants no scheme; drop the row.
        if (prefix.isEmpty() && infix.isEmpty() && suffix.isEmpty()) {
            schemeRepository.findByOrganizationId(orgId).ifPresent(schemeRepository::delete);
            log.info("Shade-code scheme cleared: org={}", orgId);
            return ShadeCodeSchemeResponse.empty();
        }

        ShadeCodeScheme scheme = schemeRepository.findByOrganizationId(orgId)
                .orElseGet(() -> ShadeCodeScheme.builder().organization(org).build());
        scheme.setPrefix(prefix);
        scheme.setInfix(infix);
        scheme.setSuffix(suffix);
        scheme = schemeRepository.save(scheme);
        log.info("Shade-code scheme saved: org={} prefix={} infix={} suffix={}", orgId, prefix, infix, suffix);
        return ShadeCodeSchemeResponse.from(scheme);
    }

    // --- Studio-side read (retailer staff, customers, guests) ---

    /**
     * The scheme the calling principal's shop uses, resolved the same way the
     * studio resolves shop combos. The empty scheme (never an error) when there
     * is no shop or the shop hasn't set one — the studio then keeps codes hidden
     * from guests exactly as before.
     */
    @Transactional(readOnly = true)
    public ShadeCodeSchemeResponse forPrincipal(String principalName, boolean guest) {
        String orgId = guest ? orgForGuest(principalName) : orgForUser(principalName);
        if (orgId == null) return ShadeCodeSchemeResponse.empty();
        return schemeRepository.findByOrganizationId(orgId)
                .map(ShadeCodeSchemeResponse::from)
                .orElseGet(ShadeCodeSchemeResponse::empty);
    }

    /** Guest principal = the redeemed access code's id; its shop owns the scheme. */
    private String orgForGuest(String accessCodeId) {
        return accessCodeRepository.findById(accessCodeId)
                .filter(code -> !code.isExpired())
                .map(code -> code.getOrganization().getId())
                .orElse(null);
    }

    /** Member of a retailer org → that org; otherwise the valid entitlement's shop. */
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

    /** Scheme parts read best in one case; store uppercase so decode is case-stable. */
    private static String normalize(String part) {
        return part == null ? "" : part.trim().toUpperCase(Locale.ROOT);
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
                    "Only the shop owner or a manager can change the shade-code scheme.");
        }
    }
}
