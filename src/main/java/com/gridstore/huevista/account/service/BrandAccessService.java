package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.repository.RetailerBrandAssignmentRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for "which paint companies may this shop offer?".
 *
 * The distributor → retailer brand assignment existed as data but was never consulted
 * anywhere: it was written by the network screen, read back by the same screen, and
 * had no effect on anything a shop could actually do. That made the whole
 * assign/revoke feature decorative. This service turns it into a real constraint at
 * the boundary that matters — what a shop can hand on to its customers.
 *
 * The anonymous marketing catalogue ({@code GET /api/shades/**}) is deliberately NOT
 * filtered by this: it is a public, heavily-cached browse surface, and making it
 * per-caller would both break the cache and change the product. Signed-in shop tools
 * ask {@code GET /api/shades/mine} instead, which applies
 * {@link #allowedBrandSlugsForUser(String)} on top of the same cached data — so a
 * restricted shop's Studio and Colour finder only ever offer the companies its
 * distributor assigned, while the shopfront stays whole and cacheable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandAccessService {

    private final OrganizationRepository orgRepository;
    private final RetailerBrandAssignmentRepository brandAssignmentRepository;
    private final UserRepository userRepository;

    /**
     * The brand display names a shop may offer.
     *
     * @return empty when the shop is unrestricted (browse everything); otherwise the
     *         exact set it carries — which may legitimately be EMPTY, meaning the
     *         distributor has revoked every brand.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandNames(String retailerOrgId) {
        Organization org = orgRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + retailerOrgId));
        if (!org.isBrandsRestricted()) {
            return Optional.empty();
        }
        Set<String> names = brandAssignmentRepository.findWithBrandByRetailerIdIn(List.of(retailerOrgId))
                .stream()
                .map(a -> a.getBrand().getName())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return Optional.of(names);
    }

    /**
     * The brand SLUGS a shop may offer — the same restriction as
     * {@link #allowedBrandNames(String)}, keyed the way the shade catalogue is.
     *
     * Filtering shades by slug rather than display name keeps the match exact:
     * brand names are free text that admins edit from the shade-upload console,
     * and one renamed company would otherwise silently drop a shop's whole
     * catalogue.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandSlugs(String retailerOrgId) {
        Organization org = orgRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + retailerOrgId));
        if (!org.isBrandsRestricted()) {
            return Optional.empty();
        }
        Set<String> slugs = brandAssignmentRepository.findWithBrandByRetailerIdIn(List.of(retailerOrgId))
                .stream()
                .map(a -> a.getBrand().getSlug())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return Optional.of(slugs);
    }

    /**
     * The brand slugs a signed-in user's shop may offer, or empty when they are not
     * a restricted retailer.
     *
     * Anyone who is not a RETAILER browses the whole catalogue: this is a
     * distributor→shop constraint, and applying it to an admin or a distributor
     * (who has no shop org of their own) would hide the catalogue from the very
     * people who curate it.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandSlugsForUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getRole() != UserRole.RETAILER) {
            return Optional.empty();
        }
        return orgRepository.findByOwnerIdAndType(userId, OrgType.RETAILER).stream().findFirst()
                .flatMap(org -> allowedBrandSlugs(org.getId()));
    }

    /**
     * Reject any requested brand the shop does not carry, so a crafted (or simply stale)
     * access-code request can't unlock a company the distributor never assigned. A blank
     * request is always fine — it means "no company filter", not "every company".
     */
    @Transactional(readOnly = true)
    public void assertBrandsOfferable(String retailerOrgId, List<String> requestedBrands) {
        if (requestedBrands == null || requestedBrands.isEmpty()) {
            return;
        }
        Optional<Set<String>> allowed = allowedBrandNames(retailerOrgId);
        if (allowed.isEmpty()) {
            return; // shop is unrestricted
        }
        Set<String> carried = allowed.get();
        List<String> rejected = requestedBrands.stream()
                .map(String::trim)
                .filter(b -> !b.isEmpty())
                .filter(b -> carried.stream().noneMatch(c -> c.equalsIgnoreCase(b)))
                .distinct()
                .toList();
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Your shop doesn't carry " + String.join(", ", rejected)
                    + ". Ask your distributor to assign "
                    + (rejected.size() == 1 ? "it" : "them") + " before offering "
                    + (rejected.size() == 1 ? "it" : "them") + " to a customer.");
        }
    }
}
