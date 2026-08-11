package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.model.RetailerBrandAssignment;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.dto.ShopBrandVisibilityResponse;
import com.gridstore.huevista.account.dto.SetVisibleBrandsRequest;
import com.gridstore.huevista.account.model.ShopVisibleBrand;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.repository.RetailerBrandAssignmentRepository;
import com.gridstore.huevista.account.repository.ShopVisibleBrandRepository;
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
    private final ShopVisibleBrandRepository visibleBrandRepository;
    private final OrgMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CustomerAccessCodeRepository codeRepository;
    private final BrandRepository brandRepository;
    private final PlanFeatureService planFeatureService;

    /**
     * The brand display names a shop may offer.
     *
     * @return empty when nothing limits the shop (browse everything); otherwise the exact
     *         set it may offer, after the distributor's assignment, the shop's own
     *         selection and its plan have all had their say. That set may legitimately be
     *         EMPTY — the distributor has revoked every brand, or the shop has switched
     *         them all off.
     */
    @Transactional(readOnly = true)
    public Optional<Set<String>> allowedBrandNames(String retailerOrgId) {
        return effectiveBrands(retailerOrgId, Brand::getName);
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
        return effectiveBrands(retailerOrgId, Brand::getSlug);
    }

    /**
     * The one place every brand restriction meets, keyed however the caller needs.
     *
     * A shop's catalogue is narrowed from three directions, and all of them hold:
     *
     * <ol>
     *   <li>The DISTRIBUTOR's grant ({@code brandsRestricted} +
     *       {@link com.gridstore.huevista.account.model.RetailerBrandAssignment}) — what
     *       this shop is permitted to carry. Decided above the shop, and not its to widen.</li>
     *   <li>The SHOP's own selection ({@code visibleBrandsRestricted} +
     *       {@link com.gridstore.huevista.account.model.ShopVisibleBrand}) — of what it may
     *       carry, which companies it actually stocks and wants shown.</li>
     *   <li>The PLAN's cap ({@link #capToPlan}) — the free tier carries one company.</li>
     * </ol>
     *
     * The order is not arbitrary. Grant before selection is what makes a revoke bite: the
     * shop's choice can only ever remove companies, never add one back, so a shop that
     * selected Berger and later lost Berger upstream shows no Berger whatever its own
     * table still holds.
     *
     * Selection before the plan cap matters just as much, and in the other direction. The
     * cap keeps ONE company, and letting the shop choose first means that one is drawn
     * from what it actually stocks. Capping first would pin the free tier's nominated
     * company and then intersect the shop's list against it — leaving a free shop that
     * stocks anything else with an empty catalogue, unable to sell, over a company it
     * never chose to carry.
     *
     * Resolving all three here rather than at each call site is the whole point —
     * everything downstream (the counter's studio, the kiosk, every access code, every
     * onboarded customer) reads through {@code allowedBrandSlugs} /
     * {@code allowedBrandNames}, so each limit lands in all of them at once and cannot be
     * forgotten in one.
     */
    private Optional<Set<String>> effectiveBrands(String retailerOrgId,
                                                  java.util.function.Function<Brand, String> key) {
        Organization org = orgRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + retailerOrgId));
        // 1) The DISTRIBUTOR's grant — what this shop is permitted to carry at all.
        //    Decided above the shop, and never widened by anything below.
        Optional<Set<String>> allowed = org.isBrandsRestricted()
                ? Optional.of(brandAssignmentRepository.findWithBrandByRetailerIdIn(List.of(retailerOrgId))
                        .stream()
                        .map(a -> key.apply(a.getBrand()))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                : Optional.empty();

        // 2) The SHOP's own selection, narrowing that grant to what it actually stocks.
        if (org.isVisibleBrandsRestricted()) {
            Set<String> shown = visibleBrandRepository.findWithBrandByRetailerId(retailerOrgId).stream()
                    .map(v -> key.apply(v.getBrand()))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (allowed.isPresent()) {
                Set<String> narrowed = new LinkedHashSet<>(allowed.get());
                narrowed.retainAll(shown);
                allowed = Optional.of(narrowed);
            } else {
                allowed = Optional.of(shown);
            }
        }

        // 3) The PLAN's cap, applied LAST and deliberately so. The free tier allows one
        //    company, and capping after the shop has chosen means that one is picked from
        //    what the shop actually stocks. The other order caps to the free tier's
        //    nominated company first and then intersects the shop's list with it — which
        //    yields NOTHING for a free shop that stocks anything else, stranding it with a
        //    catalogue it cannot sell from over a company it never chose to carry.
        return capToPlan(org, allowed, key);
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
     * Reject any requested brand the shop does not offer, so a crafted (or simply stale)
     * access-code request can't unlock a company the distributor never assigned, or one
     * the shop itself has switched off. A blank request is always fine — it means "no
     * company filter", not "every company".
     *
     * The two refusals need different words. "Ask your distributor" is useless advice for
     * a company the shop is holding back by its own setting — that one it can turn on
     * itself, and telling it otherwise sends it to argue with the wrong party.
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
        if (rejected.isEmpty()) {
            return;
        }
        String it = rejected.size() == 1 ? "it" : "them";
        String named = String.join(", ", rejected);

        // Three different things can withhold a company, and they are fixed by three
        // different people — so the refusal has to say which one actually bit. Inferring
        // "the shop hid it" from "granted but not offered" was right while those were the
        // only two limits; the plan cap made it wrong, and told a free-tier shop to go and
        // untick a box that was never ticked.
        Organization org = requireOrg(retailerOrgId);
        Set<String> granted = grantedBrandNames(retailerOrgId);
        boolean anyUngranted = rejected.stream()
                .anyMatch(b -> granted.stream().noneMatch(g -> g.equalsIgnoreCase(b)));
        if (anyUngranted) {
            throw new IllegalArgumentException("Your shop doesn't carry " + named
                    + ". Ask your distributor to assign " + it + " before offering "
                    + it + " to a customer.");
        }

        // Granted, so it is either the shop's own selection or the plan.
        if (org.isVisibleBrandsRestricted()) {
            Set<String> shown = visibleBrandRepository.findWithBrandByRetailerId(retailerOrgId).stream()
                    .map(v -> v.getBrand().getName())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean shopHidSome = rejected.stream()
                    .anyMatch(b -> shown.stream().noneMatch(s -> s.equalsIgnoreCase(b)));
            if (shopHidSome) {
                throw new IllegalArgumentException("Your shop isn't showing " + named
                        + ". Turn " + it + " back on in Shop settings → Paint companies, or leave "
                        + it + " off for customers too.");
            }
        }

        // Granted and not hidden: the plan is what is holding it back.
        throw new IllegalArgumentException("Your plan covers one paint company, so "
                + named + " can't be offered to a customer. Upgrade to a paid plan to use "
                + "everything your distributor has assigned you.");
    }

    // ── The shop's own selection ──────────────────────────────────────────────

    /**
     * What the shop's settings page renders: every company it is permitted to carry, each
     * flagged with whether it is currently shown.
     *
     * The option list is the DISTRIBUTOR's grant, not the whole platform catalogue. A shop
     * cannot show a company it was never assigned, so offering one as a checkbox would be
     * a control that silently does nothing.
     */
    @Transactional(readOnly = true)
    public ShopBrandVisibilityResponse visibilityFor(String userId, String retailerOrgId) {
        requireOwnerOrManager(userId, retailerOrgId);
        Organization org = requireOrg(retailerOrgId);
        List<Brand> grantable = grantableBrands(org);
        Set<Long> shown = visibleBrandRepository.findWithBrandByRetailerId(retailerOrgId).stream()
                .map(v -> v.getBrand().getId())
                .collect(java.util.stream.Collectors.toSet());
        boolean restricted = org.isVisibleBrandsRestricted();
        return ShopBrandVisibilityResponse.of(restricted, grantable,
                brand -> !restricted || shown.contains(brand.getId()));
    }

    /**
     * Replace the shop's selection wholesale.
     *
     * {@code showAll} lifts the shop's own limit entirely — back to "everything my
     * distributor granted me". Otherwise {@code brandIds} IS the selection, and an empty
     * one really does mean no companies at all rather than a reset; that ambiguity is
     * exactly what the flag on the organization exists to settle.
     *
     * Ids that the distributor has not granted are dropped rather than rejected. The
     * alternative is a settings page that 400s because the distributor revoked something
     * between the page loading and Save being pressed, and the shop's intent — "show these
     * of mine" — survives the drop intact.
     */
    @Transactional
    public ShopBrandVisibilityResponse setVisibility(String userId, String retailerOrgId,
                                                     SetVisibleBrandsRequest request) {
        requireOwnerOrManager(userId, retailerOrgId);
        Organization org = requireOrg(retailerOrgId);

        visibleBrandRepository.deleteByRetailerId(retailerOrgId);
        if (!request.isShowAll()) {
            Set<Long> wanted = request.getBrandIds() == null
                    ? Set.of() : Set.copyOf(request.getBrandIds());
            List<Brand> grantable = grantableBrands(org);
            List<ShopVisibleBrand> rows = grantable.stream()
                    .filter(b -> wanted.contains(b.getId()))
                    .map(b -> ShopVisibleBrand.builder().retailer(org).brand(b).build())
                    .toList();
            visibleBrandRepository.saveAll(rows);
            log.info("Shop {} now shows {} of the {} companies it carries",
                    retailerOrgId, rows.size(), grantable.size());
        } else {
            log.info("Shop {} now shows every company it carries", retailerOrgId);
        }
        org.setVisibleBrandsRestricted(!request.isShowAll());
        orgRepository.save(org);
        return visibilityFor(userId, retailerOrgId);
    }

    /** The companies the distributor has granted this shop — the pool it may choose from. */
    private List<Brand> grantableBrands(Organization org) {
        if (!org.isBrandsRestricted()) {
            return brandRepository.findAllByOrderByNameAsc();
        }
        return brandAssignmentRepository.findWithBrandByRetailerIdIn(List.of(org.getId())).stream()
                .map(RetailerBrandAssignment::getBrand)
                .sorted(java.util.Comparator.comparing(Brand::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Grant-only view, ignoring the shop's own selection — used to word a refusal. */
    private Set<String> grantedBrandNames(String retailerOrgId) {
        Organization org = requireOrg(retailerOrgId);
        return grantableBrands(org).stream()
                .map(Brand::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Organization requireOrg(String retailerOrgId) {
        return orgRepository.findById(retailerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + retailerOrgId));
    }

    /**
     * Who may change what the shop shows: its owner or a manager.
     *
     * An admin passes too — they operate every shop's settings from the console, and
     * locking them out of a per-shop switch means the only way to fix a shop that has hidden
     * every company is a database edit.
     */
    private void requireOwnerOrManager(String userId, String orgId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.getRole() == UserRole.ADMIN) {
            return;
        }
        boolean owner = membershipRepository.existsByUserIdAndOrganizationIdAndRole(
                userId, orgId, com.gridstore.huevista.account.model.OrgMemberRole.OWNER);
        boolean manager = membershipRepository.existsByUserIdAndOrganizationIdAndRole(
                userId, orgId, com.gridstore.huevista.account.model.OrgMemberRole.MANAGER);
        if (!owner && !manager) {
            throw new SecurityException("Only the shop's owner or a manager can change what it shows.");
        }
    }
}
