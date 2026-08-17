package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.dto.CustomerEntitlementResponse;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.AccessExpiredException;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns the per-customer project entitlement: how many projects a CUSTOMER may create,
 * and until when their retailer-issued access is valid.
 *
 * This governs ONE of the two ways an account gets projects — the shop route. An
 * entitlement row exists only for a customer a shop onboarded with an access code, and
 * everything here is downstream of that code: the allowance the shop assigned, the
 * window the code opened, and any top-ups the shop later added to it.
 *
 * An account with NO row here signed up on its own and buys projects individually; that
 * route is the ProjectCredit ledger and the per-project validity window, and asking such
 * an account for a code it was never given is the wrong answer. {@link #hasEntitlement}
 * is the dividing line.
 *
 * Policy:
 *  - 1 project is included by default; the shop assigns more on the code, and can top the
 *    same code up afterwards (see AccessCodeService#grantExtraProjects).
 *  - Nothing here expires. A code redeemed onto an account buys projects that stay the
 *    customer's, so the only limit is the allowance itself.
 *  - Only role == CUSTOMER is gated; retailers/distributors/admins are unrestricted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerEntitlementService {

    private final CustomerEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final ProjectGrantService grantService;
    private final com.gridstore.huevista.notification.EmailSender emailSender;

    private static final int DEFAULT_INCLUDED_PROJECTS = 1;

    /**
     * Create or top up the customer's entitlement when they redeem an access code.
     * {@code projectAllowance} is the number of projects the retailer assigned on the
     * code (at least 1).
     *
     * <p>There is no window to open or refresh: a redeemed code's projects belong to the
     * customer from then on.
     */
    @Transactional
    public void onAccessCodeRedeemed(User customer, Organization retailerOrg, int projectAllowance) {
        CustomerEntitlement existing = entitlementRepository.findByCustomerId(customer.getId()).orElse(null);
        int granted = Math.max(DEFAULT_INCLUDED_PROJECTS, projectAllowance);

        if (existing == null) {
            CustomerEntitlement ent = CustomerEntitlement.builder()
                    .customer(customer)
                    .retailerOrg(retailerOrg)
                    .projectAllowance(granted)
                    .projectsCreated(0)
                    .build();
            entitlementRepository.save(ent);
            log.info("Entitlement opened: customer={} retailer={} allowance={}",
                    customer.getId(), retailerOrg.getId(), granted);
            return;
        }

        // A second code ADDS to what the customer already has; it does not replace it.
        // Resetting projectsCreated to 0 and overwriting the allowance meant a customer who
        // had used 3 of 5 slots from shop A and then redeemed a 1-project code from shop B
        // came out with allowance 1 / used 0 — shop A's paid-for slots silently vanished,
        // and the customer's remaining balance changed in whichever direction the newest
        // code happened to point. Accumulating keeps every shop's purchase honoured.
        existing.setProjectAllowance(existing.getProjectAllowance() + granted);
        // The newest shop becomes the "managed by" link for their customer list; the
        // earlier shop keeps visibility of the work through the access code itself.
        existing.setRetailerOrg(retailerOrg);
        entitlementRepository.save(existing);
        log.info("Entitlement topped up: customer={} retailer={} +{} (allowance now {})",
                customer.getId(), retailerOrg.getId(), granted, existing.getProjectAllowance());
    }

    /**
     * Try to claim one project slot for a NEW project, ATOMICALLY. Returns whether the
     * shop route funded it.
     *
     * The conditional UPDATE replaces an older check-then-increment pair. Those were two
     * separate calls, so two parallel "create project" requests could both pass the check
     * and both create a project against a single remaining slot; now exactly one wins.
     * Non-customers pass straight through as funded — their limits live elsewhere and
     * nothing here applies to them.
     *
     * <p>It REPORTS rather than throws, so a customer who ALSO bought projects of their
     * own is offered those once the shop's allowance is spent, instead of being refused
     * outright. The refusal a shop-onboarded customer should hear is
     * {@link #projectRefusal}, but only once nothing else can pay either.
     *
     * <p>The slot is monotonic: deleting a project does not refund it.
     */
    @Transactional
    public boolean tryClaimProjectSlot(String userId) {
        if (!isCustomer(userId)) return true;
        return entitlementRepository.claimProjectSlot(userId) == 1;
    }

    /**
     * Why the shop route could not fund a project — for a caller that has already found
     * nothing else can either.
     *
     * <p>Only one reason remains: the allowance is spent. Access no longer expires, so
     * the old "your access has ended" refusal has nothing left to describe.
     */
    @Transactional(readOnly = true)
    public RuntimeException projectRefusal(String userId) {
        return outOfProjects(requireEntitlement(userId));
    }

    /**
     * Does this account hold a retailer-issued entitlement at all?
     *
     * The dividing line between the two ways an account gets projects. An account WITH a
     * row was onboarded by a shop, and its allowance comes from the code it redeemed. An
     * account WITHOUT one signed up on its own and buys projects individually — asking it
     * for an access code it was never given is the wrong answer.
     */
    @Transactional(readOnly = true)
    public boolean hasEntitlement(String userId) {
        return entitlementRepository.findByCustomerId(userId).isPresent();
    }

    /** Add {@code count} projects to a customer's allowance — the shop granting more on a code. */
    @Transactional
    public void addProjectAllowance(String customerUserId, int count) {
        if (count <= 0) return;
        entitlementRepository.findByCustomerId(customerUserId).ifPresent(ent -> {
            ent.setProjectAllowance(ent.getProjectAllowance() + count);
            entitlementRepository.save(ent);
            log.info("Entitlement allowance +{} for customer {} (now {})",
                    count, customerUserId, ent.getProjectAllowance());
        });
    }

    /**
     * The customer asking their shop for another project.
     *
     * This is the whole point of refusing the sale: a shop-onboarded customer who has run
     * out is one message away from the counter that already manages them, so the app
     * carries the message instead of a payment form. The shop adds a project in one click
     * (grant-project) and the customer carries on.
     *
     * Rate-limited by nothing beyond the customer's own patience, deliberately: the mail
     * is addressed to a shop that chose to onboard them, it names one customer, and a
     * duplicate is a nudge rather than an incident.
     */
    @Transactional(readOnly = true)
    public void requestMoreProjects(String userId) {
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No shop manages this account, so there's nobody to ask."));
        Organization shop = ent.getRetailerOrg();
        if (shop == null) {
            throw new ResourceNotFoundException("No shop manages this account, so there's nobody to ask.");
        }
        String customerName = ent.getCustomer().getName();
        membershipRepository.findUserIdsByOrganizationIdAndRole(shop.getId(), OrgMemberRole.OWNER)
                .stream().findFirst()
                .flatMap(userRepository::findById)
                .ifPresentOrElse(owner -> {
                    emailSender.send(owner.getEmail(),
                            customerName + " is asking for another project",
                            "Hi,\n\n"
                                    + customerName + " has used all "
                                    + ent.getProjectAllowance() + " project"
                                    + (ent.getProjectAllowance() == 1 ? "" : "s")
                                    + " you assigned them, and has asked for another.\n\n"
                                    + "Open your Customer portal and press \"Grant project\" on their row "
                                    + "— it takes one image credit from your plan.\n\n"
                                    + "— HueVista");
                    log.info("Customer {} asked shop {} for another project", userId, shop.getId());
                }, () -> log.warn("Customer {} asked shop {} for a project, but the shop has no owner "
                        + "account to notify", userId, shop.getId()));
    }

    /** The customer's own status (for the UI to show remaining projects / expiry). Null if none. */
    @Transactional(readOnly = true)
    public CustomerEntitlementResponse getMyEntitlement(String userId) {
        return entitlementRepository.findByCustomerId(userId)
                .map(CustomerEntitlementResponse::from)
                .orElse(null);
    }

    /**
     * Retailer: the customers this org is responsible for.
     *
     * Includes anyone holding a code this shop issued, not only those it currently
     * "manages". {@code retailerOrg} is one pointer and it moves to whichever shop
     * onboarded the customer most recently — so a customer who redeemed a second shop's
     * code disappeared from the first shop's portal entirely, taking with them the
     * projects that shop had paid for.
     */
    @Transactional(readOnly = true)
    public List<CustomerEntitlementResponse> listCustomers(String requestingUserId, String retailerOrgId) {
        requireOwnerOrManager(requestingUserId, retailerOrgId);
        return entitlementRepository.findManagedByOrCodedFrom(retailerOrgId).stream()
                .map(CustomerEntitlementResponse::from)
                .toList();
    }

    /**
     * Retailer: give a customer they manage more projects.
     *
     * Goes through {@link ProjectGrantService}, which reserves an image credit per project
     * against the shop's plan and records the grant so it can be taken back. This used to
     * be a bare {@code allowance + 1} that reserved nothing — a shop could hand out
     * unlimited projects and its subscription never noticed, while issuing a CODE for the
     * same projects charged properly. The two now cost the same thing.
     */
    @Transactional
    public CustomerEntitlementResponse grantExtraProjects(String requestingUserId, String retailerOrgId,
                                                          String customerUserId, int projects) {
        grantService.grantToCustomer(requestingUserId, retailerOrgId, customerUserId, projects);
        return entitlementRepository.findByCustomerId(customerUserId)
                .map(CustomerEntitlementResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer entitlement not found"));
    }

    /**
     * The refusal a shop-onboarded customer gets when their projects are used up.
     *
     * Deliberately NOT an offer to buy. This customer's projects were assigned by a shop
     * and paid for out of that shop's quota; the shop can add more in one click, and the
     * customer can ask for it from the app. Selling them a project direct would take
     * money for something their shop is already responsible for.
     */
    private RuntimeException outOfProjects(CustomerEntitlement ent) {
        String shop = ent.getRetailerOrg() != null ? ent.getRetailerOrg().getName() : "your shop";
        return new com.gridstore.huevista.common.exception.RetailerActionRequiredException(
                "You've used all " + ent.getProjectAllowance() + " project"
                + (ent.getProjectAllowance() == 1 ? "" : "s") + " on your code. "
                + "Ask " + shop + " to add another — they can do it from their counter.");
    }

    // --- helpers ---

    private boolean isCustomer(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        return user != null && user.getRole() == UserRole.CUSTOMER;
    }

    private CustomerEntitlement requireEntitlement(String userId) {
        return entitlementRepository.findByCustomerId(userId)
                .orElseThrow(() -> new AccessExpiredException(
                        "Your access is not set up. Ask your retailer for an access code."));
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean owner = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER);
        boolean manager = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!owner && !manager) {
            throw new SecurityException("Only org owners or managers can manage customers.");
        }
    }
}
