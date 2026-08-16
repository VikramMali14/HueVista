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
 *  - On expiry, EVERYTHING is locked (create + view + manage) until a new code is redeemed
 *    or the shop extends the one they hold.
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
     * Create or refresh the customer's entitlement when they redeem an access code.
     * {@code projectAllowance} is the number of projects the retailer assigned on the
     * code (at least 1); a freshly redeemed code starts a new period at that allowance.
     */
    @Transactional
    public void onAccessCodeRedeemed(User customer, Organization retailerOrg, int validDays, int projectAllowance) {
        CustomerEntitlement existing = entitlementRepository.findByCustomerId(customer.getId()).orElse(null);
        int granted = Math.max(DEFAULT_INCLUDED_PROJECTS, projectAllowance);

        if (existing == null) {
            CustomerEntitlement ent = CustomerEntitlement.builder()
                    .customer(customer)
                    .retailerOrg(retailerOrg)
                    .accessExpiresAt(LocalDateTime.now().plusDays(validDays))
                    .projectAllowance(granted)
                    .projectsCreated(0)
                    .build();
            entitlementRepository.save(ent);
            log.info("Entitlement opened: customer={} retailer={} allowance={} expires={}",
                    customer.getId(), retailerOrg.getId(), granted, ent.getAccessExpiresAt());
            return;
        }

        // A second code ADDS to what the customer already has; it does not replace it.
        // Resetting projectsCreated to 0 and overwriting the allowance meant a customer who
        // had used 3 of 5 slots from shop A and then redeemed a 1-project code from shop B
        // came out with allowance 1 / used 0 — shop A's paid-for slots silently vanished,
        // and the customer's remaining balance changed in whichever direction the newest
        // code happened to point. Accumulating keeps every shop's purchase honoured.
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(validDays);
        if (existing.getAccessExpiresAt() == null || newExpiry.isAfter(existing.getAccessExpiresAt())) {
            existing.setAccessExpiresAt(newExpiry);
        }
        existing.setProjectAllowance(existing.getProjectAllowance() + granted);
        // The newest shop becomes the "managed by" link for their customer list; the
        // earlier shop keeps visibility of the work through the access code itself.
        existing.setRetailerOrg(retailerOrg);
        entitlementRepository.save(existing);
        log.info("Entitlement extended: customer={} retailer={} +{} (allowance now {}) expires={}",
                customer.getId(), retailerOrg.getId(), granted,
                existing.getProjectAllowance(), existing.getAccessExpiresAt());
    }

    /**
     * Establish (or extend) the entitlement when a guest signs up and claims the
     * projects they created under an access code. Without this, the freshly-created
     * CUSTOMER account owns the claimed projects but has NO entitlement row, and
     * every project read throws "Your access is not set up" — the exact opposite
     * of the "create an account to keep your work" promise.
     *
     * - No existing entitlement: create one mirroring what the guest already had —
     *   the code's retailer org, the code's own expiry (not a fresh window), the
     *   default allowance, and the claimed projects counted as used.
     * - Existing entitlement: only extend, never downgrade. Expiry becomes the later
     *   of the two, and allowance + used both grow by the claimed count so the
     *   customer's remaining slots are unchanged while the claimed work stays visible.
     */
    @Transactional
    public void onGuestProjectsClaimed(User customer, CustomerAccessCode code, int projectsClaimed) {
        LocalDateTime codeExpiry = code.getExpiresAt();
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customer.getId()).orElse(null);
        if (ent == null) {
            ent = CustomerEntitlement.builder()
                    .customer(customer)
                    .retailerOrg(code.getOrganization())
                    // Column is NOT NULL; a code with no expiry (shouldn't happen) yields
                    // an already-expired entitlement rather than a constraint violation.
                    .accessExpiresAt(codeExpiry != null ? codeExpiry : LocalDateTime.now())
                    .projectAllowance(Math.max(DEFAULT_INCLUDED_PROJECTS, projectsClaimed))
                    .projectsCreated(projectsClaimed)
                    .build();
        } else {
            if (codeExpiry != null
                    && (ent.getAccessExpiresAt() == null || codeExpiry.isAfter(ent.getAccessExpiresAt()))) {
                ent.setAccessExpiresAt(codeExpiry);
            }
            if (ent.getRetailerOrg() == null) {
                ent.setRetailerOrg(code.getOrganization());
            }
            ent.setProjectAllowance(ent.getProjectAllowance() + projectsClaimed);
            ent.setProjectsCreated(ent.getProjectsCreated() + projectsClaimed);
        }
        entitlementRepository.save(ent);
        log.info("Entitlement established from guest claim: customer={} retailer={} claimed={} expires={}",
                customer.getId(), code.getOrganization().getId(), projectsClaimed, ent.getAccessExpiresAt());
    }

    /**
     * Guarantee a redeemed customer HAS an entitlement, without touching what they
     * have already used. Called on every re-entry into an access-code account: the
     * account is signed in from the code alone, and a customer with no entitlement
     * row is locked out of every project read ("Your access is not set up") — the
     * exact opposite of what redeeming their code just promised.
     *
     * - No row: create one mirroring the code — its shop, its own expiry (never a
     *   fresh window), its assigned quota, nothing used yet.
     * - Existing row: only ever fill gaps. Usage is never reset and the window is
     *   never shortened; the code's expiry can only extend an earlier one, which in
     *   practice is a no-op since the entitlement was opened at redeem time.
     */
    @Transactional
    public void ensureEntitlementForCode(User customer, CustomerAccessCode code) {
        LocalDateTime codeExpiry = code.getExpiresAt();
        int codeAllowance = Math.max(DEFAULT_INCLUDED_PROJECTS, code.getProjectQuota());
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customer.getId()).orElse(null);
        if (ent == null) {
            ent = CustomerEntitlement.builder()
                    .customer(customer)
                    .retailerOrg(code.getOrganization())
                    // Column is NOT NULL; a code with no expiry (shouldn't happen) yields an
                    // already-expired entitlement rather than a constraint violation.
                    .accessExpiresAt(codeExpiry != null ? codeExpiry : LocalDateTime.now())
                    .projectAllowance(codeAllowance)
                    .projectsCreated(0)
                    .build();
            log.info("Entitlement repaired on code re-entry: customer={} retailer={} allowance={} expires={}",
                    customer.getId(), code.getOrganization().getId(), codeAllowance, ent.getAccessExpiresAt());
        } else {
            if (ent.getRetailerOrg() == null) {
                ent.setRetailerOrg(code.getOrganization());
            }
            if (codeExpiry != null
                    && (ent.getAccessExpiresAt() == null || codeExpiry.isAfter(ent.getAccessExpiresAt()))) {
                ent.setAccessExpiresAt(codeExpiry);
            }
            if (ent.getProjectAllowance() < codeAllowance) {
                ent.setProjectAllowance(codeAllowance);
            }
        }
        entitlementRepository.save(ent);
    }

    /**
     * Guard for ANY project access (view/manage). Enforces the full expiry lock.
     *
     * A customer with NO entitlement row at all is deliberately let through: they
     * have never held shop access, so they own nothing to lock, and throwing here
     * turned their dashboard into a 403 error panel sitting next to a banner that
     * (correctly) invited them to redeem a code. Creating a project still requires
     * a real entitlement — see {@link #assertCanCreateProject}.
     */
    @Transactional(readOnly = true)
    public void assertAccessValid(String userId) {
        if (!isCustomer(userId)) return;
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(userId).orElse(null);
        if (ent != null && ent.isExpired()) {
            throw new AccessExpiredException(
                    "Your access has ended. Ask your retailer for a new access code.");
        }
    }

    /**
     * Try to claim one project slot for a NEW project: expiry + allowance, taken
     * ATOMICALLY. Returns whether the shop route funded it.
     *
     * The conditional UPDATE replaces an older check-then-increment pair. Those were two
     * separate calls, so two parallel "create project" requests could both pass the check
     * and both create a project against a single remaining slot; now exactly one wins.
     * Non-customers pass straight through as funded — their limits live elsewhere and
     * nothing here applies to them.
     *
     * <p>It REPORTS rather than throws, which matters for one account in particular: a
     * customer who bought projects on their own and later redeemed a shop's code holds
     * both. Throwing from here made the shop's ten-day window the only thing that
     * counted — when it closed, the rooms and credits they had paid for themselves went
     * with it, because the caller never got as far as asking about them. The refusal a
     * shop-onboarded customer should hear is still {@link #projectRefusal}, but only once
     * nothing else can pay either.
     *
     * <p>The slot is monotonic: deleting a project does not refund it.
     */
    @Transactional
    public boolean tryClaimProjectSlot(String userId) {
        if (!isCustomer(userId)) return true;
        return entitlementRepository.claimProjectSlot(userId, LocalDateTime.now()) == 1;
    }

    /**
     * Why the shop route could not fund a project — for a caller that has already found
     * nothing else can either.
     *
     * The two cases read very differently to the person holding the code, so they stay
     * two exceptions: a window that closed is 403 "your access has ended" (a new code
     * fixes it), while an allowance used up is the 402 ASK_RETAILER refusal that puts a
     * "grant one more" request in front of the counter.
     */
    @Transactional(readOnly = true)
    public RuntimeException projectRefusal(String userId) {
        CustomerEntitlement ent = requireEntitlement(userId);
        if (ent.isExpired()) {
            return new AccessExpiredException(
                    "Your access has ended. Ask your retailer for a new access code.");
        }
        return outOfProjects(ent);
    }

    /**
     * Does this account hold a retailer-issued entitlement at all?
     *
     * The dividing line between the two ways an account gets projects. An account WITH a
     * row was onboarded by a shop, and its allowance and expiry come from the code it
     * redeemed. An account WITHOUT one signed up on its own and buys projects
     * individually — asking it for an access code it was never given is the wrong answer.
     */
    @Transactional(readOnly = true)
    public boolean hasEntitlement(String userId) {
        return entitlementRepository.findByCustomerId(userId).isPresent();
    }

    /**
     * Extend a customer's access window to at least {@code until} — used when the shop
     * adds days to the code they redeemed. Only ever lengthens: a shop topping up a code
     * must never shorten access the customer already has from somewhere else.
     */
    @Transactional
    public void extendAccessTo(String customerUserId, LocalDateTime until) {
        if (until == null) return;
        entitlementRepository.findByCustomerId(customerUserId).ifPresent(ent -> {
            if (ent.getAccessExpiresAt() == null || until.isAfter(ent.getAccessExpiresAt())) {
                ent.setAccessExpiresAt(until);
                entitlementRepository.save(ent);
                log.info("Entitlement window extended to {} for customer {}", until, customerUserId);
            }
        });
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
