package com.gridstore.huevista.hierarchy.service;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.model.DistributorRetailerLink;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.model.RetailerBrandAssignment;
import com.gridstore.huevista.account.model.RetailerFeatureAssignment;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.DistributorRetailerLinkRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.repository.RetailerBrandAssignmentRepository;
import com.gridstore.huevista.account.repository.RetailerFeatureAssignmentRepository;
import com.gridstore.huevista.account.service.AccountService;
import com.gridstore.huevista.account.service.BrandAccessService;
import com.gridstore.huevista.account.service.FeatureAccessService;
import com.gridstore.huevista.account.service.HouseDistributorService;
import com.gridstore.huevista.auth.dto.AdminUserResponse;
import com.gridstore.huevista.auth.dto.CreateDistributorRequest;
import com.gridstore.huevista.auth.dto.CreatePainterRequest;
import com.gridstore.huevista.auth.dto.CreateRetailerRequest;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.AuthService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.hierarchy.dto.DistributorOptionResponse;
import com.gridstore.huevista.hierarchy.dto.MyAccessResponse;
import com.gridstore.huevista.hierarchy.dto.RetailerBrandOption;
import com.gridstore.huevista.hierarchy.dto.RetailerFeatureOption;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.model.PainterProfile;
import com.gridstore.huevista.painter.model.PainterRetailerLink;
import com.gridstore.huevista.painter.repository.PainterRetailerLinkRepository;
import com.gridstore.huevista.painter.service.PainterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gridstore.huevista.hierarchy.dto.NetworkNodeResponse;
import com.gridstore.huevista.hierarchy.dto.NetworkReportResponse;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The account hierarchy: ADMIN → DISTRIBUTOR → RETAILER → PAINTER.
 *
 * Each level provisions the next (an admin creates distributors, a distributor
 * creates its retailers, a retailer creates its painters), every created account
 * records who created it, and {@link #network(String)} reports the viewer's
 * downline as a tree — the whole platform for an admin, their own subtree for
 * everyone else. Customers stay outside the tree: they enter by redeeming a
 * shop access code (see AccessCodeService), which this report counts per shop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchyService {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final DistributorRetailerLinkRepository distributorLinkRepository;
    private final RetailerBrandAssignmentRepository brandAssignmentRepository;
    private final RetailerFeatureAssignmentRepository featureAssignmentRepository;
    private final BrandAccessService brandAccessService;
    private final FeatureAccessService featureAccessService;
    private final BrandRepository brandRepository;
    private final PainterRetailerLinkRepository painterLinkRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final CustomerEntitlementRepository customerEntitlementRepository;
    private final AccountService accountService;
    private final AuthService authService;
    private final HouseDistributorService houseDistributorService;
    private final PainterService painterService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final com.gridstore.huevista.billing.service.BillingService billingService;

    /** The free tier runs for a week — same window a new shop gets. */
    private static final int FREE_TIER_DAYS = 7;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    // ── Creation flows ────────────────────────────────────────────────────

    /** ADMIN-only (enforced at the endpoint): create a DISTRIBUTOR account + org. */
    @Transactional
    public AdminUserResponse createDistributor(String creatorUserId, CreateDistributorRequest request) {
        String email = com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use: " + email);
        }
        User user = User.builder()
                .name(request.getName())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true) // admin-vetted
                .phoneNumber(blankToNull(request.getPhone()))
                .role(UserRole.DISTRIBUTOR)
                .createdById(creatorUserId)
                .build();
        userRepository.save(user);
        accountService.provisionDistributorOrg(user.getId(), request.getCompanyName(),
                request.getCity(), request.getState());
        sendWelcomeEmail(user, "distributor", request.getCompanyName());
        log.info("Admin {} created DISTRIBUTOR {} ({})", creatorUserId, user.getEmail(), request.getCompanyName());
        return AdminUserResponse.from(user);
    }

    /**
     * ADMIN or DISTRIBUTOR: create a RETAILER (shop) account. Reuses the admin
     * provisioning path (org + trial + welcome email), then records provenance;
     * a distributor's new shop is additionally auto-linked to their org so it
     * lands in their downline immediately.
     *
     * <p>Brand and page access are applied here too, in the same transaction, so a
     * shop is never briefly live with the run of the whole product before the
     * distributor tightens it. Both are skipped when there is no distributor to
     * grant them (an admin creating an unlinked shop) and both default to
     * unrestricted, so callers that don't send them are unaffected.
     */
    /**
     * Put an existing shop back on the free tier: seven days, three projects.
     *
     * A distributor onboarding shops needs a way to restart the trial for one that let it
     * lapse before deciding — that conversation happens at the distributor, not at
     * support. Scoped to shops the distributor actually manages; admins can do it for any.
     *
     * Deliberately a no-op when the shop already holds a live plan (grantTrial returns the
     * existing one): handing a paying shop a free tier would supersede what they bought.
     */
    @Transactional
    public com.gridstore.huevista.billing.dto.SubscriptionResponse grantFreeTier(
            String actorUserId, String retailerUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actorUserId));
        User retailer = userRepository.findById(retailerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + retailerUserId));
        if (retailer.getRole() != UserRole.RETAILER) {
            throw new IllegalArgumentException("The free tier is a shop plan.");
        }
        if (actor.getRole() == UserRole.DISTRIBUTOR) {
            requireManagedShop(actorUserId, retailerUserId);
        } else if (actor.getRole() != UserRole.ADMIN) {
            throw new SecurityException("Only admins and distributors can assign the free tier.");
        }
        return billingService.grantTrial(retailerUserId,
                com.gridstore.huevista.billing.model.Plan.FREE, FREE_TIER_DAYS);
    }

    /** The distributor a shop is filed under, if any. */
    @Transactional(readOnly = true)
    public java.util.Optional<Organization> distributorOf(String retailerOrgId) {
        return distributorLinkRepository.findByRetailerId(retailerOrgId).stream()
                .findFirst().map(DistributorRetailerLink::getDistributor);
    }

    /** The shop must sit under this distributor's org, or it is not theirs to change. */
    private void requireManagedShop(String distributorUserId, String retailerUserId) {
        Organization distributorOrg = firstOrgOf(distributorUserId, OrgType.DISTRIBUTOR)
                .orElseThrow(() -> new IllegalStateException(
                        "Your distributor organization was not found — contact the administrator."));
        Organization retailerOrg = firstOrgOf(retailerUserId, OrgType.RETAILER)
                .orElseThrow(() -> new ResourceNotFoundException("That shop has no organization."));
        boolean managed = distributorLinkRepository
                .findByDistributorId(distributorOrg.getId()).stream()
                .anyMatch(link -> link.getRetailer().getId().equals(retailerOrg.getId()));
        if (!managed) {
            throw new SecurityException("That shop is not in your network.");
        }
    }

    @Transactional
    public AdminUserResponse createRetailer(String creatorUserId, CreateRetailerRequest request) {
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + creatorUserId));
        Organization distributorOrg = resolveCreationDistributor(creator, request.getDistributorOrgId());

        AdminUserResponse created = authService.adminCreateRetailer(request);
        return finishRetailerSetup(created.getId(), creatorUserId, creator.getRole().name(), distributorOrg,
                request.getBrandIds(), request.isBrandsUnrestricted(),
                request.getFeatures(), request.isFeaturesUnrestricted());
    }

    /**
     * Which distributor a newly created shop belongs under.
     *
     * <p>A DISTRIBUTOR always creates into their own org — they cannot file a shop
     * under a competitor, so the requested id is ignored rather than validated. An
     * ADMIN chooses: any distributor org by id, or none, which means the house
     * distributor. "None" used to mean a shop with no distributor at all, dangling
     * outside every downline; it now means the platform is the distributor, which is
     * the same thing said honestly and keeps the network a single tree.
     */
    private Organization resolveCreationDistributor(User creator, String requestedOrgId) {
        if (creator.getRole() == UserRole.DISTRIBUTOR) {
            return firstOrgOf(creator.getId(), OrgType.DISTRIBUTOR)
                    .orElseThrow(() -> new IllegalStateException(
                            "Your distributor organization was not found — contact the administrator."));
        }
        if (creator.getRole() != UserRole.ADMIN) {
            throw new SecurityException("Only admins and distributors can create shop accounts.");
        }
        return resolveDistributorOrHouse(requestedOrgId);
    }

    /**
     * A distributor org by id, or the house distributor when none was named. Shared
     * by admin creation and by shop-request approval, so both file a shop the same way.
     */
    @Transactional
    public Organization resolveDistributorOrHouse(String requestedOrgId) {
        if (requestedOrgId != null && !requestedOrgId.isBlank()) {
            return orgRepository.findById(requestedOrgId.trim())
                    .filter(o -> o.getType() == OrgType.DISTRIBUTOR)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Distributor not found: " + requestedOrgId));
        }
        return houseDistributorService.ensureHouseOrg().orElse(null);
    }

    /**
     * Link a freshly created shop to its distributor, record who created it, and apply
     * the brand/page grants — the part every creation path shares once the user exists.
     *
     * <p>Grants are applied whether or not a distributor is behind them. Skipping them
     * for a shop with no distributor left it not merely unrestricted but
     * UNRESTRICTABLE: this was the only place they were set at creation, and the
     * editors refused afterwards for want of a distributor to attribute them to. A null
     * distributor just means "granted by the platform".
     */
    private AdminUserResponse finishRetailerSetup(String retailerUserId, String creatorUserId,
                                                  String creatorLabel, Organization distributorOrg,
                                                  List<Long> brandIds, boolean brandsUnrestricted,
                                                  List<String> features, boolean featuresUnrestricted) {
        User retailerUser = userRepository.findById(retailerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + retailerUserId));
        retailerUser.setCreatedById(creatorUserId);
        userRepository.save(retailerUser);

        Organization retailerOrg = firstOrgOf(retailerUser.getId(), OrgType.RETAILER)
                .orElseThrow(() -> new IllegalStateException(
                        "Shop organization was not provisioned for " + retailerUser.getEmail()));
        if (distributorOrg != null) {
            distributorLinkRepository.save(DistributorRetailerLink.builder()
                    .distributor(distributorOrg)
                    .retailer(retailerOrg)
                    .build());
        }
        applyBrandGrant(distributorOrg, retailerOrg, brandIds, brandsUnrestricted);
        applyFeatureGrant(distributorOrg, retailerOrg, features, featuresUnrestricted);
        log.info("{} created RETAILER {} (distributor={}, brandsUnrestricted={}, featuresUnrestricted={})",
                creatorLabel, retailerUser.getEmail(),
                distributorOrg != null ? distributorOrg.getName() : "none",
                brandsUnrestricted, featuresUnrestricted);
        return AdminUserResponse.from(retailerUser);
    }

    /**
     * Provision the shop a verified account request asked for, under {@code
     * distributorOrgId} (or the house distributor when that is blank).
     *
     * <p>Takes the password HASH the requester's own password was stored as, so the
     * shop signs in with the password they chose on the form. No plaintext exists
     * anywhere in this path, and no plan beyond the free tier can come out of it.
     *
     * @param approverUserId the admin who approved, or null when the 24-hour deadline did
     */
    @Transactional
    public AdminUserResponse createRetailerFromRequest(String approverUserId, String name, String email,
                                                       String phone, String shopName, String city,
                                                       String state, String passwordHash,
                                                       String distributorOrgId) {
        Organization distributorOrg = resolveDistributorOrHouse(distributorOrgId);
        AdminUserResponse created = authService.provisionRetailer(
                name, email, phone, shopName, city, state, passwordHash, false);
        return finishRetailerSetup(created.getId(), approverUserId,
                approverUserId != null ? "Admin" : "Auto-approval",
                distributorOrg, List.of(), true, List.of(), true);
    }

    /**
     * Every distributor an admin can file a shop under, house org first.
     *
     * <p>The house org is created on demand here rather than only at the first shop
     * that needs it, so the picker always offers it — an admin should not have to
     * create a shop before "HueVista Direct" appears as somewhere to put one.
     */
    @Transactional
    public List<DistributorOptionResponse> distributorOptions() {
        houseDistributorService.ensureHouseOrg();
        List<Organization> orgs = orgRepository.findAll().stream()
                .filter(o -> o.getType() == OrgType.DISTRIBUTOR)
                .toList();
        Map<String, Long> shopCounts = new HashMap<>();
        for (DistributorRetailerLink link : distributorLinkRepository.findAll()) {
            shopCounts.merge(link.getDistributor().getId(), 1L, Long::sum);
        }
        Map<String, User> owners = batchUsers(orgs, List.of());
        return orgs.stream()
                .map(o -> {
                    User owner = owners.get(o.getOwner().getId());
                    return DistributorOptionResponse.builder()
                            .orgId(o.getId())
                            .name(o.getName())
                            .city(o.getCity())
                            .state(o.getState())
                            .ownerName(owner != null ? owner.getName() : null)
                            .ownerEmail(owner != null ? owner.getEmail() : null)
                            .shopCount(shopCounts.getOrDefault(o.getId(), 0L))
                            .house(houseDistributorService.isHouse(o))
                            .build();
                })
                // House first, then by name — the default sits at the top of the dropdown.
                .sorted(Comparator.comparing(DistributorOptionResponse::isHouse).reversed()
                        .thenComparing(DistributorOptionResponse::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * RETAILER-only: create a PAINTER account already linked (ACTIVE) to the
     * retailer's shop — the direct-provisioning sibling of the invitation-code
     * flow in PainterInvitationService, which stays available for painters who
     * sign themselves up.
     */
    @Transactional
    public AdminUserResponse createPainter(String creatorUserId, CreatePainterRequest request) {
        Organization retailerOrg = firstOrgOf(creatorUserId, OrgType.RETAILER)
                .orElseThrow(() -> new SecurityException(
                        "Only a shop (retailer) owner can create painter accounts."));

        String email = com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use: " + email);
        }
        User user = User.builder()
                .name(request.getName())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true) // shop-vetted
                .phoneNumber(blankToNull(request.getPhone()))
                .role(UserRole.PAINTER)
                .createdById(creatorUserId)
                .build();
        userRepository.save(user);

        PainterProfile profile = painterService.ensureProfile(user);
        profile.setPhone(blankToNull(request.getPhone()));

        LocalDateTime now = LocalDateTime.now();
        painterLinkRepository.save(PainterRetailerLink.builder()
                .painter(user)
                .retailer(retailerOrg)
                .status(PainterLinkStatus.ACTIVE)
                .invitedAt(now)
                .acceptedAt(now)
                .build());

        sendWelcomeEmail(user, "painter", retailerOrg.getName());
        log.info("Retailer {} created PAINTER {} for shop {}", creatorUserId, user.getEmail(), retailerOrg.getId());
        return AdminUserResponse.from(user);
    }

    // ── Brand assignments (distributor → shop) ────────────────────────────

    /** The distributor + retailer orgs a caller is allowed to act on for one shop. */
    private record ManageableShop(Organization distributor, Organization retailer) {}

    /**
     * Resolve the shop and check the caller may manage its brands: an ADMIN can
     * manage any shop; a DISTRIBUTOR only shops linked to their own org.
     */
    private ManageableShop resolveManageableShop(String callerUserId, String retailerOrgId) {
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + callerUserId));
        Organization retailer = orgRepository.findById(retailerOrgId)
                .filter(o -> o.getType() == OrgType.RETAILER)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + retailerOrgId));

        if (caller.getRole() == UserRole.ADMIN) {
            Organization distributor = distributorLinkRepository.findByRetailerId(retailerOrgId).stream()
                    .findFirst().map(DistributorRetailerLink::getDistributor).orElse(null);
            return new ManageableShop(distributor, retailer);
        }
        if (caller.getRole() == UserRole.DISTRIBUTOR) {
            Organization distributor = firstOrgOf(callerUserId, OrgType.DISTRIBUTOR)
                    .orElseThrow(() -> new IllegalStateException(
                            "Your distributor organization was not found — contact the administrator."));
            if (!distributorLinkRepository.existsByDistributorIdAndRetailerId(distributor.getId(), retailerOrgId)) {
                throw new SecurityException("That shop is not in your network.");
            }
            return new ManageableShop(distributor, retailer);
        }
        throw new SecurityException("Only admins and distributors can manage a shop's brands.");
    }

    /**
     * The companies this shop can be given, with a flag for the ones it already has.
     *
     * Only companies with shades in the catalogue are offered — see
     * {@link #grantableBrands()} — plus any this shop already holds. That exception
     * matters: the editor saves the whole selection at once, so a granted company
     * dropped from the list would be silently revoked on the distributor's next save.
     */
    @Transactional(readOnly = true)
    public List<RetailerBrandOption> retailerBrandOptions(String callerUserId, String retailerOrgId) {
        resolveManageableShop(callerUserId, retailerOrgId);
        Set<Long> assigned = brandAssignmentRepository.findByRetailerId(retailerOrgId).stream()
                .map(a -> a.getBrand().getId()) // id comes off the lazy proxy without a query
                .collect(Collectors.toSet());

        Map<Long, Brand> options = new LinkedHashMap<>();
        for (Brand b : brandRepository.findWithShadesOrderByNameAsc()) {
            options.put(b.getId(), b);
        }
        if (!assigned.isEmpty()) {
            brandRepository.findAllById(assigned).forEach(b -> options.putIfAbsent(b.getId(), b));
        }
        return options.values().stream()
                .sorted(Comparator.comparing(Brand::getName, String.CASE_INSENSITIVE_ORDER))
                .map(b -> RetailerBrandOption.of(b, assigned.contains(b.getId())))
                .toList();
    }

    /**
     * Replace a shop's brand selection wholesale.
     *
     * {@code unrestricted} lifts the limit entirely; otherwise {@code brandIds} becomes
     * the shop's complete allowance — and an EMPTY list now means exactly that, zero
     * brands. It previously meant "clear the restriction", so a distributor removing the
     * last brand from a shop silently granted them the entire catalogue instead of
     * revoking their access. Returns the refreshed option list.
     */
    @Transactional
    public List<RetailerBrandOption> assignBrands(String callerUserId, String retailerOrgId,
                                                  List<Long> brandIds, boolean unrestricted) {
        ManageableShop shop = resolveManageableShop(callerUserId, retailerOrgId);
        // A null distributor means an admin is granting directly — the assignment simply
        // records no distributor. This used to refuse outright, which made every
        // admin-created shop permanently unrestrictable: creation skips the grant when
        // there is no distributor to attribute it to, and this was the only other way in.
        int count = applyBrandGrant(shop.distributor(), shop.retailer(), brandIds, unrestricted);
        log.info("{} set brands for shop {}: unrestricted={} count={}",
                callerUserId, retailerOrgId, unrestricted, count);
        return retailerBrandOptions(callerUserId, retailerOrgId);
    }

    /**
     * Write one shop's brand selection. Shared by {@link #assignBrands} and shop
     * creation so both paths store the restriction identically — the flag on the org
     * plus the rows, never one without the other.
     *
     * @return how many brands ended up assigned
     */
    private int applyBrandGrant(Organization distributor, Organization retailer,
                                List<Long> brandIds, boolean unrestricted) {
        // Wipe the old selection, then re-create from the (deduped) request.
        brandAssignmentRepository.deleteByRetailerId(retailer.getId());
        brandAssignmentRepository.flush();
        List<Long> ids = unrestricted || brandIds == null ? List.of()
                : brandIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            for (Brand brand : brandRepository.findAllById(ids)) {
                brandAssignmentRepository.save(RetailerBrandAssignment.builder()
                        .distributor(distributor)
                        .retailer(retailer)
                        .brand(brand)
                        .build());
            }
        }
        retailer.setBrandsRestricted(!unrestricted);
        orgRepository.save(retailer);
        return ids.size();
    }

    /**
     * Everything a distributor could grant, before any shop exists to grant it to.
     *
     * The shop-creation form needs the same two checklists the editors show, but
     * {@link #retailerBrandOptions} and {@link #retailerFeatureOptions} both resolve a
     * shop first. These are the pre-creation twins: same DTOs, nothing assigned yet.
     *
     * <p>Only companies that actually have shades are offered. The brands table also
     * carries companies seeded for their product lines alone, and listing those let a
     * distributor limit a new shop to a company with nothing in it — a shop that opens
     * to an empty catalogue and no way to tell why. A company appears here the moment
     * its shades are uploaded, so the list stays current without a code change.
     */
    @Transactional(readOnly = true)
    public List<RetailerBrandOption> grantableBrands() {
        return brandRepository.findWithShadesOrderByNameAsc().stream()
                .map(b -> RetailerBrandOption.of(b, false))
                .toList();
    }

    /** @see #grantableBrands() */
    @Transactional(readOnly = true)
    public List<RetailerFeatureOption> grantableFeatures() {
        return java.util.Arrays.stream(AppFeature.values())
                .map(f -> RetailerFeatureOption.of(f, false))
                .toList();
    }

    // ── Page access (distributor → shop) ──────────────────────────────────

    /** Every grantable page with a flag for whether this shop currently has it. */
    @Transactional(readOnly = true)
    public List<RetailerFeatureOption> retailerFeatureOptions(String callerUserId, String retailerOrgId) {
        ManageableShop shop = resolveManageableShop(callerUserId, retailerOrgId);
        boolean restricted = shop.retailer().isFeaturesRestricted();
        Set<AppFeature> assigned = featureAssignmentRepository.findByRetailerId(retailerOrgId).stream()
                .map(RetailerFeatureAssignment::getFeature)
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(AppFeature.class)));
        return java.util.Arrays.stream(AppFeature.values())
                // An unrestricted shop opens everything, so every box reads as ticked —
                // the editor then starts from "all on" instead of misreporting a shop
                // with full access as having nothing.
                .map(f -> RetailerFeatureOption.of(f, !restricted || assigned.contains(f)))
                .toList();
    }

    /**
     * Replace a shop's page selection wholesale.
     *
     * Same three-state contract as {@link #assignBrands}: {@code unrestricted} lifts the
     * limit, an empty {@code features} list really does mean "no optional pages", and the
     * two are separate so revoking the last page can't read as granting everything.
     * Unknown feature names are ignored rather than fatal, so one stale key from an older
     * frontend can't fail the whole save.
     */
    @Transactional
    public List<RetailerFeatureOption> assignFeatures(String callerUserId, String retailerOrgId,
                                                      List<String> features, boolean unrestricted) {
        ManageableShop shop = resolveManageableShop(callerUserId, retailerOrgId);
        // Null distributor = granted by an admin directly; see assignBrands.
        int count = applyFeatureGrant(shop.distributor(), shop.retailer(), features, unrestricted);
        log.info("{} set pages for shop {}: unrestricted={} count={}",
                callerUserId, retailerOrgId, unrestricted, count);
        return retailerFeatureOptions(callerUserId, retailerOrgId);
    }

    /**
     * Write one shop's page selection. Shared by {@link #assignFeatures} and shop
     * creation, mirroring {@link #applyBrandGrant}.
     *
     * @return how many pages ended up switched on
     */
    private int applyFeatureGrant(Organization distributor, Organization retailer,
                                  List<String> features, boolean unrestricted) {
        featureAssignmentRepository.deleteByRetailerId(retailer.getId());
        featureAssignmentRepository.flush();
        Set<AppFeature> granted = java.util.EnumSet.noneOf(AppFeature.class);
        if (!unrestricted && features != null) {
            for (String raw : features) {
                AppFeature.fromKey(raw).ifPresent(granted::add);
            }
        }
        for (AppFeature feature : granted) {
            featureAssignmentRepository.save(RetailerFeatureAssignment.builder()
                    .distributor(distributor)
                    .retailer(retailer)
                    .feature(feature)
                    .build());
        }
        retailer.setFeaturesRestricted(!unrestricted);
        orgRepository.save(retailer);
        return granted.size();
    }

    // ── What the caller may see ───────────────────────────────────────────

    /**
     * The signed-in caller's own brand + page access — one call the frontend makes to
     * decide which nav tabs to render and which pages to admit. Non-retailers always
     * come back unrestricted; this is a distributor→shop constraint and never applies
     * upward.
     */
    @Transactional(readOnly = true)
    public MyAccessResponse myAccess(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        MyAccessResponse.MyAccessResponseBuilder out = MyAccessResponse.builder()
                .role(user.getRole().name())
                .brandsRestricted(false)
                .allowedBrands(List.of())
                .featuresRestricted(false)
                .allowedFeatures(List.of())
                .allowedPaths(List.of());

        Organization shop = featureAccessService.retailerOrgOf(userId).orElse(null);
        if (shop == null) {
            return out.build();
        }
        out.orgId(shop.getId()).orgName(shop.getName());

        brandAccessService.allowedBrandNames(shop.getId()).ifPresent(names ->
                out.brandsRestricted(true).allowedBrands(List.copyOf(names)));
        featureAccessService.allowedFeatures(shop.getId()).ifPresent(features -> out
                .featuresRestricted(true)
                .allowedFeatures(features.stream().map(AppFeature::name).toList())
                .allowedPaths(features.stream().map(AppFeature::getPath).toList()));
        return out.build();
    }

    /**
     * Attach each shop's granted brands and pages to its report node, along with the
     * flags that say whether those lists are limits at all.
     *
     * The flags come off the {@link Organization} rows already loaded for the tree, so
     * this stays two queries regardless of network size.
     */
    private void attachAssignedAccess(Collection<NetworkNodeResponse> retailerNodes,
                                      Map<String, Organization> orgsById) {
        List<String> orgIds = retailerNodes.stream()
                .map(NetworkNodeResponse::getOrgId).filter(java.util.Objects::nonNull).toList();
        if (orgIds.isEmpty()) return;

        Map<String, List<String>> brandsByOrg = new HashMap<>();
        for (RetailerBrandAssignment a : brandAssignmentRepository.findWithBrandByRetailerIdIn(orgIds)) {
            brandsByOrg.computeIfAbsent(a.getRetailer().getId(), k -> new java.util.ArrayList<>())
                    .add(a.getBrand().getName());
        }
        Map<String, List<String>> featuresByOrg = new HashMap<>();
        for (RetailerFeatureAssignment a : featureAssignmentRepository.findByRetailerIdIn(orgIds)) {
            featuresByOrg.computeIfAbsent(a.getRetailer().getId(), k -> new java.util.ArrayList<>())
                    .add(a.getFeature().getLabel());
        }

        for (NetworkNodeResponse node : retailerNodes) {
            List<String> names = brandsByOrg.getOrDefault(node.getOrgId(), new java.util.ArrayList<>());
            names.sort(String::compareToIgnoreCase);
            node.setAssignedBrands(names);

            List<String> features = featuresByOrg.getOrDefault(node.getOrgId(), new java.util.ArrayList<>());
            features.sort(String::compareToIgnoreCase);
            node.setAssignedFeatures(features);

            Organization org = orgsById.get(node.getOrgId());
            node.setBrandsRestricted(org != null && org.isBrandsRestricted());
            node.setFeaturesRestricted(org != null && org.isFeaturesRestricted());
        }
    }

    /** Index the retailer orgs a report already loaded, for the access-flag lookup above. */
    private static Map<String, Organization> byId(List<Organization> orgs) {
        return orgs.stream().collect(Collectors.toMap(Organization::getId, Function.identity(), (a, b) -> a));
    }

    // ── Network report ────────────────────────────────────────────────────

    /** Role-scoped downline report — see {@link NetworkReportResponse}. */
    @Transactional(readOnly = true)
    public NetworkReportResponse network(String viewerUserId) {
        User viewer = userRepository.findById(viewerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + viewerUserId));
        return switch (viewer.getRole()) {
            case ADMIN -> adminNetwork();
            case DISTRIBUTOR -> distributorNetwork(viewer);
            case RETAILER -> retailerNetwork(viewer);
            default -> throw new SecurityException("The network report is for admins, distributors and retailers.");
        };
    }

    private NetworkReportResponse adminNetwork() {
        List<Organization> orgs = orgRepository.findAll();
        List<Organization> distributorOrgs = orgs.stream().filter(o -> o.getType() == OrgType.DISTRIBUTOR).toList();
        List<Organization> retailerOrgs = orgs.stream().filter(o -> o.getType() == OrgType.RETAILER).toList();
        List<DistributorRetailerLink> links = distributorLinkRepository.findAll();
        List<PainterRetailerLink> painterLinks = painterLinkRepository.findByStatus(PainterLinkStatus.ACTIVE);

        Map<String, NetworkNodeResponse> retailerNodes =
                buildRetailerNodes(retailerOrgs, painterLinks, batchUsers(orgs, painterLinks));
        attachAssignedAccess(retailerNodes.values(), byId(retailerOrgs));

        Map<String, NetworkNodeResponse> distributorNodes = new LinkedHashMap<>();
        Map<String, User> owners = batchUsers(distributorOrgs, List.of());
        for (Organization d : distributorOrgs) {
            NetworkNodeResponse node = orgNode(d, owners.get(d.getOwner().getId()), UserRole.DISTRIBUTOR);
            // Flagged rather than hidden: the house org carries every shop no partner
            // brought in, so it belongs in the tree — but it is an organization, not a
            // distributor account, which is why the distributor total excludes it.
            node.setHouse(houseDistributorService.isHouse(d));
            distributorNodes.put(d.getId(), node);
        }

        Set<String> linkedRetailerOrgIds = new HashSet<>();
        for (DistributorRetailerLink link : links) {
            NetworkNodeResponse distributor = distributorNodes.get(link.getDistributor().getId());
            NetworkNodeResponse retailer = retailerNodes.get(link.getRetailer().getId());
            if (distributor == null || retailer == null) continue;
            distributor.getChildren().add(retailer);
            linkedRetailerOrgIds.add(link.getRetailer().getId());
        }
        distributorNodes.values().forEach(HierarchyService::rollUp);

        List<NetworkNodeResponse> roots = new java.util.ArrayList<>(distributorNodes.values());
        retailerNodes.forEach((orgId, node) -> {
            if (!linkedRetailerOrgIds.contains(orgId)) roots.add(node);
        });

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("distributors", userRepository.countByRole(UserRole.DISTRIBUTOR));
        totals.put("retailers", userRepository.countByRole(UserRole.RETAILER));
        totals.put("painters", userRepository.countByRole(UserRole.PAINTER));
        // Summed off the shop nodes, not countByRole: a CUSTOMER account whose shop was
        // deleted still has the role but no longer belongs to any branch of the tree, so
        // the old count could exceed everything the report actually showed.
        addCustomerTotals(totals, retailerNodes.values());

        return NetworkReportResponse.builder()
                .viewerRole(UserRole.ADMIN.name())
                .totals(totals)
                .roots(roots)
                .build();
    }

    private NetworkReportResponse distributorNetwork(User viewer) {
        Organization distributorOrg = firstOrgOf(viewer.getId(), OrgType.DISTRIBUTOR)
                .orElseThrow(() -> new IllegalStateException(
                        "Your distributor organization was not found — contact the administrator."));

        List<Organization> retailerOrgs = distributorLinkRepository.findByDistributorId(distributorOrg.getId())
                .stream().map(DistributorRetailerLink::getRetailer).toList();
        List<PainterRetailerLink> painterLinks = retailerOrgs.isEmpty() ? List.of()
                : painterLinkRepository.findByRetailerIdInAndStatus(
                        retailerOrgs.stream().map(Organization::getId).toList(), PainterLinkStatus.ACTIVE);

        Map<String, NetworkNodeResponse> retailerNodes =
                buildRetailerNodes(retailerOrgs, painterLinks, batchUsers(retailerOrgs, painterLinks));
        attachAssignedAccess(retailerNodes.values(), byId(retailerOrgs));

        NetworkNodeResponse self = orgNode(distributorOrg, viewer, UserRole.DISTRIBUTOR);
        self.getChildren().addAll(retailerNodes.values());
        rollUp(self);

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("retailers", self.getRetailerCount());
        totals.put("painters", self.getPainterCount());
        addCustomerTotals(totals, retailerNodes.values());

        return NetworkReportResponse.builder()
                .viewerRole(UserRole.DISTRIBUTOR.name())
                .totals(totals)
                .roots(List.of(self))
                .build();
    }

    private NetworkReportResponse retailerNetwork(User viewer) {
        Organization retailerOrg = firstOrgOf(viewer.getId(), OrgType.RETAILER)
                .orElseThrow(() -> new IllegalStateException(
                        "Your shop organization was not found — contact the administrator."));

        List<PainterRetailerLink> painterLinks =
                painterLinkRepository.findByRetailerIdAndStatus(retailerOrg.getId(), PainterLinkStatus.ACTIVE);

        Map<String, NetworkNodeResponse> nodes =
                buildRetailerNodes(List.of(retailerOrg), painterLinks, batchUsers(List.of(retailerOrg), painterLinks));
        // A shop sees its own grant too — "which companies am I set up for?" is a
        // question the shop asks more often than its distributor does.
        attachAssignedAccess(nodes.values(), byId(List.of(retailerOrg)));
        NetworkNodeResponse self = nodes.get(retailerOrg.getId());

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("painters", self.getPainterCount());
        totals.put("customers", self.getCustomerCount());
        totals.put("codesIssued", self.getCodesIssued());
        totals.put("codesRedeemed", self.getCodesRedeemed());

        return NetworkReportResponse.builder()
                .viewerRole(UserRole.RETAILER.name())
                .totals(totals)
                .roots(List.of(self))
                .build();
    }

    // ── Tree assembly helpers ─────────────────────────────────────────────

    /**
     * Retailer nodes with their painters AND their customers as children, keyed by
     * retailer org id.
     *
     * <p>Customers are the last link in the chain this report exists to show. They were
     * represented only as a code count, which says how many were handed out but not who
     * holds one or whether it did anything — so an admin could see that a shop issued
     * forty codes and had no way to tell forty working customers from forty dead ones.
     */
    private Map<String, NetworkNodeResponse> buildRetailerNodes(List<Organization> retailerOrgs,
                                                                List<PainterRetailerLink> painterLinks,
                                                                Map<String, User> users) {
        List<String> orgIds = retailerOrgs.stream().map(Organization::getId).toList();
        Map<String, long[]> codeStats = codeStatsByOrg(orgIds);

        Map<String, List<NetworkNodeResponse>> paintersByOrg = painterLinks.stream().collect(
                Collectors.groupingBy(l -> l.getRetailer().getId(),
                        Collectors.mapping(l -> painterNode(users.get(l.getPainter().getId()), l),
                                Collectors.toList())));
        Map<String, List<NetworkNodeResponse>> customersByOrg = customerNodesByOrg(orgIds);

        Map<String, NetworkNodeResponse> nodes = new LinkedHashMap<>();
        for (Organization org : retailerOrgs) {
            NetworkNodeResponse node = orgNode(org, users.get(org.getOwner().getId()), UserRole.RETAILER);
            List<NetworkNodeResponse> painters = paintersByOrg.getOrDefault(org.getId(), List.of());
            List<NetworkNodeResponse> customers = customersByOrg.getOrDefault(org.getId(), List.of());
            node.getChildren().addAll(painters);
            node.getChildren().addAll(customers);
            // Counted from the two lists rather than from children.size(), which was
            // the painter count only while painters were the only children.
            node.setPainterCount(painters.size());
            node.setCustomerCount(customers.size());
            long[] codes = codeStats.getOrDefault(org.getId(), new long[]{0, 0});
            node.setCodesIssued(codes[0]);
            node.setCodesRedeemed(codes[1]);
            nodes.put(org.getId(), node);
        }
        return nodes;
    }

    /**
     * One batched load of every customer these shops manage, as report nodes.
     *
     * <p>Keyed on the entitlement's managing shop, so each customer appears exactly
     * once. A customer who has redeemed codes from two shops has a real relationship
     * with both — and both see them in their own portal — but a tree needs one answer
     * to "whose customer is this?", or the same person is counted at every level above
     * each shop.
     */
    private Map<String, List<NetworkNodeResponse>> customerNodesByOrg(List<String> retailerOrgIds) {
        if (retailerOrgIds.isEmpty()) return Map.of();
        List<CustomerEntitlement> entitlements =
                customerEntitlementRepository.findByRetailerOrgIdIn(retailerOrgIds);
        if (entitlements.isEmpty()) return Map.of();

        Map<String, User> customers = userRepository
                .findAllById(entitlements.stream().map(e -> e.getCustomer().getId()).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Map<String, List<NetworkNodeResponse>> byOrg = new LinkedHashMap<>();
        for (CustomerEntitlement e : entitlements) {
            byOrg.computeIfAbsent(e.getRetailerOrg().getId(), k -> new java.util.ArrayList<>())
                    .add(customerNode(customers.get(e.getCustomer().getId()), e));
        }
        return byOrg;
    }

    /**
     * One customer as a report row.
     *
     * <p>The address is withheld for an account created by redeeming a code: it is
     * synthesised from the code purely to give the row a unique key, and showing it
     * would present a machine identifier as somewhere a shop could write.
     */
    private static NetworkNodeResponse customerNode(User customer, CustomerEntitlement entitlement) {
        return NetworkNodeResponse.builder()
                .userId(customer != null ? customer.getId() : null)
                .name(customer != null ? customer.getName() : "—")
                .email(customer != null ? com.gridstore.huevista.auth.util.Emails.publicEmailOf(customer) : null)
                .phone(customer != null ? customer.getPhoneNumber() : null)
                .role(UserRole.CUSTOMER.name())
                .joinedAt(entitlement.getCreatedAt())
                .projectAllowance(entitlement.getProjectAllowance())
                .projectsUsed(entitlement.getProjectsCreated())
                .accessExpiresAt(entitlement.getAccessExpiresAt())
                .build();
    }

    private static NetworkNodeResponse orgNode(Organization org, User owner, UserRole role) {
        return NetworkNodeResponse.builder()
                .userId(owner != null ? owner.getId() : null)
                .name(owner != null ? owner.getName() : "—")
                .email(owner != null ? owner.getEmail() : null)
                .phone(owner != null ? owner.getPhoneNumber() : null)
                .role(role.name())
                .joinedAt(org.getCreatedAt())
                .orgId(org.getId())
                .orgName(org.getName())
                .city(org.getCity())
                .state(org.getState())
                .build();
    }

    private static NetworkNodeResponse painterNode(User painter, PainterRetailerLink link) {
        return NetworkNodeResponse.builder()
                .userId(painter != null ? painter.getId() : null)
                .name(painter != null ? painter.getName() : "—")
                .email(painter != null ? painter.getEmail() : null)
                .phone(painter != null ? painter.getPhoneNumber() : null)
                .role(UserRole.PAINTER.name())
                .joinedAt(link.getAcceptedAt() != null ? link.getAcceptedAt() : link.getCreatedAt())
                .build();
    }

    /** Sums a parent's direct children into its own rollup counters. */
    private static void rollUp(NetworkNodeResponse parent) {
        long retailers = 0, painters = 0, customers = 0, issued = 0, redeemed = 0;
        for (NetworkNodeResponse child : parent.getChildren()) {
            if (UserRole.RETAILER.name().equals(child.getRole())) retailers++;
            painters += child.getPainterCount();
            customers += child.getCustomerCount();
            issued += child.getCodesIssued();
            redeemed += child.getCodesRedeemed();
        }
        parent.setRetailerCount(retailers);
        parent.setPainterCount(painters);
        parent.setCustomerCount(customers);
        parent.setCodesIssued(issued);
        parent.setCodesRedeemed(redeemed);
    }

    /**
     * Customer and code totals for a report header.
     *
     * <p>Customers are summed off the shop nodes rather than counted globally, so the
     * number always agrees with the tree below it: a viewer whose scope is three shops
     * gets those three shops' customers, and the header can't say 40 while the rows
     * add up to 12.
     */
    private static void addCustomerTotals(Map<String, Long> totals,
                                          Collection<NetworkNodeResponse> retailerNodes) {
        totals.put("customers", retailerNodes.stream().mapToLong(NetworkNodeResponse::getCustomerCount).sum());
        totals.put("codesIssued", retailerNodes.stream().mapToLong(NetworkNodeResponse::getCodesIssued).sum());
        totals.put("codesRedeemed", retailerNodes.stream().mapToLong(NetworkNodeResponse::getCodesRedeemed).sum());
    }

    /** One batched user load for org owners + painters — avoids per-node lazy fetches. */
    private Map<String, User> batchUsers(List<Organization> orgs, List<PainterRetailerLink> painterLinks) {
        Set<String> ids = new HashSet<>();
        orgs.forEach(o -> ids.add(o.getOwner().getId()));
        painterLinks.forEach(l -> ids.add(l.getPainter().getId()));
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** Per-org [issued, redeemed] access-code counts. */
    private Map<String, long[]> codeStatsByOrg(List<String> orgIds) {
        if (orgIds.isEmpty()) return Map.of();
        Map<String, long[]> stats = new HashMap<>();
        for (Object[] row : accessCodeRepository.issuedAndRedeemedByOrgIds(orgIds)) {
            stats.put((String) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return stats;
    }

    private java.util.Optional<Organization> firstOrgOf(String ownerUserId, OrgType type) {
        return orgRepository.findByOwnerIdAndType(ownerUserId, type).stream().findFirst();
    }

    // ── Misc ──────────────────────────────────────────────────────────────

    /**
     * Best-effort welcome mail — mirrors the shop welcome in AuthService: never
     * fails creation and never contains the initial password (the creator hands
     * it over out-of-band; the mail points at "Forgot password" instead).
     */
    private void sendWelcomeEmail(User user, String accountKind, String orgLabel) {
        try {
            String url = firstFrontendOrigin();
            emailSender.send(user.getEmail(),
                    "Your HueVista " + accountKind + " account is ready",
                    "Hi " + user.getName() + ",\n\n"
                            + "Your HueVista " + accountKind + " account"
                            + (orgLabel != null && !orgLabel.isBlank() ? " for \"" + orgLabel + "\"" : "")
                            + " is ready.\n\n"
                            + "Sign in:  " + url + "/sign-in\n"
                            + "Email:    " + user.getEmail() + "\n\n"
                            + "Your initial password comes from the person who set up your account. "
                            + "Prefer your own? Use \"Forgot password\" on the sign-in page to set one:\n"
                            + url + "/sign-in/forgot\n\n"
                            + "— HueVista");
        } catch (Exception e) {
            log.warn("Welcome email to {} failed: {}", user.getEmail(), e.getMessage());
        }
    }

    private String firstFrontendOrigin() {
        if (allowedOrigins != null) {
            for (String o : allowedOrigins.split(",")) {
                String t = o.trim();
                if (!t.isEmpty() && !"*".equals(t)) {
                    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
                }
            }
        }
        return "http://localhost:3000";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
