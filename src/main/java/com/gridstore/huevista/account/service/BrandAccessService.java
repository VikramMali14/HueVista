package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.repository.RetailerBrandAssignmentRepository;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
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
 *
 * Two limits meet here, and both bite: the distributor's assignment (another business's
 * decision about this shop) and the shop's own PLAN, which on the free tier carries a
 * single paint company — see {@link #capToPlan}. They are combined in this one place so
 * that everything downstream of a shop inherits the answer automatically: the access
 * codes it issues, the guests who redeem them, and the customers who signed in through
 * one all read the shop's companies through the methods below.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandAccessService {

    private final OrganizationRepository orgRepository;
    private final RetailerBrandAssignmentRepository brandAssignmentRepository;
    private final UserRepository userRepository;
    private final CustomerAccessCodeRepository codeRepository;
    private final BrandRepository brandRepository;
    private final PlanFeatureService planFeatureService;

    /**
     * The brand display names a shop may offer.
     *
     * @return empty when nothing limits the shop (browse everything); otherwise the exact
     *         set it carries, after both the distributor's assignment and the shop's plan
     *         have had their say. That set may legitimately be EMPTY, meaning the
     *         distributor has revoked every brand.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandNames(String retailerOrgId) {
        Organization org = orgRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + retailerOrgId));
        if (!org.isBrandsRestricted()) {
            return capToPlan(org, Optional.empty(), Brand::getName);
        }
        Set<String> names = brandAssignmentRepository.findWithBrandByRetailerIdIn(List.of(retailerOrgId))
                .stream()
                .map(a -> a.getBrand().getName())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return capToPlan(org, Optional.of(names), Brand::getName);
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
            return capToPlan(org, Optional.empty(), Brand::getSlug);
        }
        Set<String> slugs = brandAssignmentRepository.findWithBrandByRetailerIdIn(List.of(retailerOrgId))
                .stream()
                .map(a -> a.getBrand().getSlug())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return capToPlan(org, Optional.of(slugs), Brand::getSlug);
    }

    /**
     * Narrow what the distributor assigned by what the shop's own PLAN includes.
     *
     * The free tier carries ONE paint company ({@link Plan#FREE_TIER_BRAND_SLUG}); every
     * paid tier carries the whole assignment. Applied here, at the single place that
     * answers "which companies may this shop offer?", so it reaches the shop's own
     * catalogue AND everything downstream of it — the codes it issues, the guests who
     * redeem them, the customers who signed in through one — without any of those callers
     * having to know a plan exists.
     *
     * Three cases the obvious implementation gets wrong, and what happens instead:
     *
     * <ul>
     *   <li>An UNRESTRICTED free shop (no distributor limit at all) must not read as
     *       "browse everything" — it is capped to the free tier's company, which is the
     *       whole point. That is why the no-restriction branch comes through here rather
     *       than returning {@code Optional.empty()} directly.</li>
     *   <li>A free shop whose distributor assigned it Berger but not Asian Paints would be
     *       left with an EMPTY catalogue by a plain intersection — a shop that can sell
     *       nothing, over a company it never chose. It keeps the first company it actually
     *       carries instead: still one company, which is what the tier says.</li>
     *   <li>A catalogue with no Asian Paints loaded at all (a fresh install, a re-import in
     *       progress) falls back the same way — the first company there is — rather than
     *       handing every free shop in the system an empty catalogue over a seeding job.</li>
     * </ul>
     *
     * @param assigned what the distributor granted, or empty for "no distributor limit"
     * @param key      how to name a brand — display name or slug, matching the caller
     */
    private Optional<Set<String>> capToPlan(
            Organization org,
            Optional<Set<String>> assigned,
            java.util.function.Function<Brand, String> key
    ) {
        if (org.getType() != OrgType.RETAILER) {
            return assigned;
        }
        // The owner's plan is the shop's plan. A missing owner (data we should never
        // have) reads as unchanged rather than as "the free tier", so a broken row can
        // never silently strip a paying shop's catalogue.
        String ownerId = org.getOwner() == null ? null : org.getOwner().getId();
        if (ownerId == null || planFeatureService.planOf(ownerId).isFullCatalogue()) {
            return assigned;
        }
        List<String> catalogue = brandRepository.findAllByOrderByNameAsc().stream()
                .filter(b -> Plan.FREE_TIER_BRAND_SLUG.equalsIgnoreCase(b.getSlug()))
                .map(key)
                .filter(java.util.Objects::nonNull)
                .toList();
        String freeTierBrand = catalogue.isEmpty() ? null : catalogue.get(0);

        if (assigned.isEmpty()) {
            // No distributor limit: the plan alone decides, and it says one company.
            return Optional.of(freeTierBrand != null
                    ? oneOf(freeTierBrand)
                    : firstOf(brandRepository.findAllByOrderByNameAsc().stream()
                            .map(key)
                            .filter(java.util.Objects::nonNull)
                            .toList()));
        }
        Set<String> carried = assigned.get();
        if (freeTierBrand != null && carried.contains(freeTierBrand)) {
            return Optional.of(oneOf(freeTierBrand));
        }
        // Doesn't carry the free tier's company — cap at the first one it does.
        return Optional.of(firstOf(carried));
    }

    /** A single-brand allowance. */
    private static Set<String> oneOf(String brand) {
        return new LinkedHashSet<>(List.of(brand));
    }

    /** The first brand of {@code brands} as a single-brand allowance; empty if there is none. */
    private static Set<String> firstOf(Collection<String> brands) {
        return brands.stream().findFirst().map(BrandAccessService::oneOf).orElseGet(LinkedHashSet::new);
    }

    /**
     * The brand slugs a signed-in caller may browse.
     *
     * Two different restrictions meet here, and both have to bite:
     *
     * <ul>
     *   <li>A RETAILER is limited to the companies their distributor assigned them, and
     *       then by their own plan — the free tier carries one company.</li>
     *   <li>A CUSTOMER is limited to the companies their shop unlocked on the access code
     *       they redeemed — which was, until now, enforced nowhere at all. The shop picks
     *       companies (and individual products) when issuing the code, and for a customer
     *       who redeemed into an ACCOUNT — the primary walk-in route — none of it reached
     *       the catalogue they were served: they saw the entire platform. Only the
     *       anonymous-guest path applied it, and only through a cookie in the guest's own
     *       browser, which is a suggestion rather than a restriction.</li>
     * </ul>
     *
     * Everyone else browses whole: admins and distributors curate the catalogue, and
     * painters work across whatever their shop stocks.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandSlugsForUser(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            // Not a user id — a guest principal is their access code's id. Answering
            // "unrestricted" for an unknown principal is the one wrong answer here.
            return allowedBrandSlugsForGuest(userId);
        }
        if (user.getRole() == UserRole.CUSTOMER) {
            return codeRepository.findFirstByUsedByUserIdOrderByCreatedAtDesc(userId)
                    .flatMap(this::brandSlugsOnCode);
        }
        if (user.getRole() != UserRole.RETAILER) {
            return Optional.empty();
        }
        return orgRepository.findByOwnerIdAndType(userId, OrgType.RETAILER).stream().findFirst()
                .flatMap(org -> allowedBrandSlugs(org.getId()));
    }

    /**
     * The brand slugs an anonymous guest may browse — the companies their access code
     * unlocked, intersected with what the issuing shop itself carries.
     *
     * The intersection matters: a code can outlive its shop's own grant, and a shop whose
     * distributor has since revoked a company must not keep handing it out through codes
     * printed earlier.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandSlugsForGuest(String accessCodeId) {
        return codeRepository.findById(accessCodeId).flatMap(this::brandSlugsOnCode);
    }

    /** A code's own company restriction, narrowed by the issuing shop's. */
    private Optional<Set<String>> brandSlugsOnCode(CustomerAccessCode code) {
        Optional<Set<String>> shopSlugs = allowedBrandSlugs(code.getOrganization().getId());
        List<String> onCode = code.getAllowedBrandList();
        if (onCode.isEmpty()) {
            return shopSlugs; // no per-customer filter; the shop's own limit still applies
        }
        Set<String> wanted = onCode.stream()
                .map(name -> name.trim().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> slugs = brandRepository.findAllByOrderByNameAsc().stream()
                .filter(b -> b.getName() != null
                        && wanted.contains(b.getName().trim().toLowerCase(java.util.Locale.ROOT)))
                .map(Brand::getSlug)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        shopSlugs.ifPresent(slugs::retainAll);
        return Optional.of(slugs);
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
