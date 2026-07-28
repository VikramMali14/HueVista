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
        return describe(orgId);
    }

    /** This shop's full display settings: the code pattern, and whether names show. */
    private ShadeCodeSchemeResponse describe(String orgId) {
        boolean showNames = organizationRepository.findById(orgId)
                .map(Organization::isShowShadeNames)
                .orElse(true);
        return schemeRepository.findByOrganizationId(orgId)
                .map(scheme -> ShadeCodeSchemeResponse.from(scheme, showNames))
                .orElseGet(() -> ShadeCodeSchemeResponse.empty(showNames));
    }

    @Transactional
    public ShadeCodeSchemeResponse update(String userId, String orgId, UpdateShadeCodeSchemeRequest req) {
        requireOwnerOrManager(userId, orgId);
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));

        String prefix = normalize(req.getPrefix());
        String infix = normalize(req.getInfix());
        String suffix = normalize(req.getSuffix());

        // Names are a separate switch on the org, so clearing the pattern (which deletes
        // its row) leaves the name choice standing. Null means "not editing that".
        if (req.getShowNames() != null && req.getShowNames() != org.isShowShadeNames()) {
            org.setShowShadeNames(req.getShowNames());
            organizationRepository.save(org);
            log.info("Shade names {} for org={}", req.getShowNames() ? "shown" : "hidden", orgId);
        }

        // All three parts empty = the shop wants no pattern; drop the row.
        if (prefix.isEmpty() && infix.isEmpty() && suffix.isEmpty()) {
            schemeRepository.findByOrganizationId(orgId).ifPresent(schemeRepository::delete);
            log.info("Shade-code scheme cleared: org={}", orgId);
            return ShadeCodeSchemeResponse.empty(org.isShowShadeNames());
        }

        ShadeCodeScheme scheme = schemeRepository.findByOrganizationId(orgId)
                .orElseGet(() -> ShadeCodeScheme.builder().organization(org).build());
        scheme.setPrefix(prefix);
        scheme.setInfix(infix);
        scheme.setSuffix(suffix);
        scheme = schemeRepository.save(scheme);
        log.info("Shade-code scheme saved: org={} prefix={} infix={} suffix={}", orgId, prefix, infix, suffix);
        return ShadeCodeSchemeResponse.from(scheme, org.isShowShadeNames());
    }

    // --- Studio-side read (retailer staff, customers, guests) ---

    /**
     * The display settings the calling principal's shop uses, resolved the same way the
     * studio resolves shop combos: their own shop for retailer staff and painters, their
     * retailer's for an entitled customer, the issuing shop's for a guest.
     *
     * This answers for EVERYONE under the shop, not only guests. A shop that builds its
     * own code scheme is replacing the paint company's numbering with its own — so its
     * own staff, its painters and its customers all need to be reading the same codes the
     * counter reads. Resolving this for guests alone meant the one pattern the shop
     * defined appeared on exactly one surface, and the shop saw manufacturer codes their
     * customers had never been shown.
     *
     * The empty response (never an error) when there is no shop to ask.
     */
    @Transactional(readOnly = true)
    public ShadeCodeSchemeResponse forPrincipal(String principalName, boolean guest) {
        String orgId = guest ? orgForGuest(principalName) : orgForUser(principalName);
        if (orgId == null) return ShadeCodeSchemeResponse.empty();
        return describe(orgId);
    }

    /**
     * How the shop behind a SHARED project presents a colour, for the anonymous
     * viewer of a share link.
     *
     * A share link is handed out by someone under a shop, so the page it opens is
     * still that shop's shopfront: if the shop hides paint names, the share page
     * must hide them too, and if it runs its own numbering, that is the numbering
     * to show. Without this the one screen the shop has least control over — the
     * link forwarded to a spouse, a builder, a WhatsApp group — was the one screen
     * still naming the paint company's colours.
     *
     * The viewer has no session, so nothing here can be resolved from the caller;
     * it is resolved from the project's owner instead — the user who made it, or
     * the access code a guest made it under.
     *
     * @param userId       the project's owner, or null for a guest's project
     * @param accessCodeId the code it was made under, or null
     */
    @Transactional(readOnly = true)
    public ShadeCodeSchemeResponse forSharedProject(String userId, String accessCodeId) {
        String orgId = userId != null ? orgForUser(userId) : null;
        if (orgId == null && accessCodeId != null) {
            // Deliberately not the expiry-filtered lookup below: a share link can
            // outlive the code that created the room, and an expired code must not
            // quietly turn the shop's presentation back into the manufacturer's.
            orgId = accessCodeRepository.findById(accessCodeId)
                    .map(code -> code.getOrganization().getId())
                    .orElse(null);
        }
        if (orgId == null) return ShadeCodeSchemeResponse.empty();
        return describe(orgId);
    }

    /** Guest principal = the redeemed access code's id; its shop owns the scheme. */
    private String orgForGuest(String accessCodeId) {
        return accessCodeRepository.findById(accessCodeId)
                .filter(code -> !code.isExpired())
                .map(code -> code.getOrganization().getId())
                .orElse(null);
    }

    /**
     * Member of a retailer org → that org; otherwise the shop that onboarded them.
     *
     * The entitlement lookup deliberately does NOT require the window to still be open.
     * A customer whose access has lapsed still sees their saved rooms in view-only, and
     * showing manufacturer codes there — on the very colours that were presented under
     * the shop's own numbering — would leak exactly what the scheme exists to withhold,
     * at the moment the shop has least control over the screen.
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
                .filter(ent -> ent.getRetailerOrg() != null)
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
