package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.repository.RetailerBrandAssignmentRepository;
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
 * The public marketing catalogue ({@code GET /api/shades/**}) is deliberately NOT
 * filtered by this: it is an anonymous, heavily-cached browse surface, and making it
 * per-caller would both break the cache and change the product. The constraint is
 * enforced where it has consequence instead: a shop cannot unlock a brand it does not
 * carry on a customer access code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandAccessService {

    private final OrganizationRepository orgRepository;
    private final RetailerBrandAssignmentRepository brandAssignmentRepository;

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
