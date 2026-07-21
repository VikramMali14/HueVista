package com.gridstore.huevista.hierarchy.service;

import com.gridstore.huevista.account.model.DistributorRetailerLink;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.DistributorRetailerLinkRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.account.service.AccountService;
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
import com.gridstore.huevista.notification.EmailSender;
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
    private final PainterRetailerLinkRepository painterLinkRepository;
    private final CustomerAccessCodeRepository accessCodeRepository;
    private final AccountService accountService;
    private final AuthService authService;
    private final PainterService painterService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

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
     */
    @Transactional
    public AdminUserResponse createRetailer(String creatorUserId, CreateRetailerRequest request) {
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + creatorUserId));

        Organization distributorOrg = null;
        if (creator.getRole() == UserRole.DISTRIBUTOR) {
            distributorOrg = firstOrgOf(creatorUserId, OrgType.DISTRIBUTOR)
                    .orElseThrow(() -> new IllegalStateException(
                            "Your distributor organization was not found — contact the administrator."));
        } else if (creator.getRole() != UserRole.ADMIN) {
            throw new SecurityException("Only admins and distributors can create shop accounts.");
        }

        AdminUserResponse created = authService.adminCreateRetailer(request);
        User retailerUser = userRepository.findById(created.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + created.getId()));
        retailerUser.setCreatedById(creatorUserId);
        userRepository.save(retailerUser);

        if (distributorOrg != null) {
            Organization retailerOrg = firstOrgOf(retailerUser.getId(), OrgType.RETAILER)
                    .orElseThrow(() -> new IllegalStateException(
                            "Shop organization was not provisioned for " + retailerUser.getEmail()));
            distributorLinkRepository.save(DistributorRetailerLink.builder()
                    .distributor(distributorOrg)
                    .retailer(retailerOrg)
                    .build());
            log.info("Distributor {} created and linked RETAILER {}", creatorUserId, retailerUser.getEmail());
        }
        return created;
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

        Map<String, NetworkNodeResponse> distributorNodes = new LinkedHashMap<>();
        Map<String, User> owners = batchUsers(distributorOrgs, List.of());
        for (Organization d : distributorOrgs) {
            distributorNodes.put(d.getId(), orgNode(d, owners.get(d.getOwner().getId()), UserRole.DISTRIBUTOR));
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
        totals.put("customers", userRepository.countByRole(UserRole.CUSTOMER));
        addCodeTotals(totals, retailerNodes.values());

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

        NetworkNodeResponse self = orgNode(distributorOrg, viewer, UserRole.DISTRIBUTOR);
        self.getChildren().addAll(retailerNodes.values());
        rollUp(self);

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("retailers", self.getRetailerCount());
        totals.put("painters", self.getPainterCount());
        addCodeTotals(totals, retailerNodes.values());

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
        NetworkNodeResponse self = nodes.get(retailerOrg.getId());

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("painters", self.getPainterCount());
        totals.put("codesIssued", self.getCodesIssued());
        totals.put("codesRedeemed", self.getCodesRedeemed());

        return NetworkReportResponse.builder()
                .viewerRole(UserRole.RETAILER.name())
                .totals(totals)
                .roots(List.of(self))
                .build();
    }

    // ── Tree assembly helpers ─────────────────────────────────────────────

    /** Retailer nodes (with painter children + code counts), keyed by retailer org id. */
    private Map<String, NetworkNodeResponse> buildRetailerNodes(List<Organization> retailerOrgs,
                                                                List<PainterRetailerLink> painterLinks,
                                                                Map<String, User> users) {
        Map<String, long[]> codeStats = codeStatsByOrg(
                retailerOrgs.stream().map(Organization::getId).toList());

        Map<String, List<NetworkNodeResponse>> paintersByOrg = painterLinks.stream().collect(
                Collectors.groupingBy(l -> l.getRetailer().getId(),
                        Collectors.mapping(l -> painterNode(users.get(l.getPainter().getId()), l),
                                Collectors.toList())));

        Map<String, NetworkNodeResponse> nodes = new LinkedHashMap<>();
        for (Organization org : retailerOrgs) {
            NetworkNodeResponse node = orgNode(org, users.get(org.getOwner().getId()), UserRole.RETAILER);
            node.getChildren().addAll(paintersByOrg.getOrDefault(org.getId(), List.of()));
            node.setPainterCount(node.getChildren().size());
            long[] codes = codeStats.getOrDefault(org.getId(), new long[]{0, 0});
            node.setCodesIssued(codes[0]);
            node.setCodesRedeemed(codes[1]);
            nodes.put(org.getId(), node);
        }
        return nodes;
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
        long retailers = 0, painters = 0, issued = 0, redeemed = 0;
        for (NetworkNodeResponse child : parent.getChildren()) {
            if (UserRole.RETAILER.name().equals(child.getRole())) retailers++;
            painters += child.getPainterCount();
            issued += child.getCodesIssued();
            redeemed += child.getCodesRedeemed();
        }
        parent.setRetailerCount(retailers);
        parent.setPainterCount(painters);
        parent.setCodesIssued(issued);
        parent.setCodesRedeemed(redeemed);
    }

    private static void addCodeTotals(Map<String, Long> totals, Collection<NetworkNodeResponse> retailerNodes) {
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
