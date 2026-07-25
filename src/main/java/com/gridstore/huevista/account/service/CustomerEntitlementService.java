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
 * Policy (confirmed with the product owner):
 *  - 1 project is included by default.
 *  - Extra projects are unlocked by the retailer granting one, OR the customer buying one.
 *  - On expiry, EVERYTHING is locked (create + view + manage) until a new code is redeemed.
 *  - Only role == CUSTOMER is gated; retailers/distributors/admins are unrestricted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerEntitlementService {

    private final CustomerEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;

    private static final int DEFAULT_INCLUDED_PROJECTS = 1;

    /**
     * Create or refresh the customer's entitlement when they redeem an access code.
     * {@code projectAllowance} is the number of projects the retailer assigned on the
     * code (at least 1); a freshly redeemed code starts a new period at that allowance.
     */
    @Transactional
    public void onAccessCodeRedeemed(User customer, Organization retailerOrg, int validDays, int projectAllowance) {
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customer.getId())
                .orElseGet(() -> CustomerEntitlement.builder().customer(customer).build());
        ent.setRetailerOrg(retailerOrg);
        ent.setAccessExpiresAt(LocalDateTime.now().plusDays(validDays));
        // A freshly redeemed code starts a new period: reset to the assigned allowance.
        ent.setProjectAllowance(Math.max(DEFAULT_INCLUDED_PROJECTS, projectAllowance));
        ent.setProjectsCreated(0);
        entitlementRepository.save(ent);
        log.info("Entitlement set: customer={} retailer={} allowance={} expires={}",
                customer.getId(), retailerOrg.getId(), ent.getProjectAllowance(), ent.getAccessExpiresAt());
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

    /** Guard for ANY project access (view/manage). Enforces the full expiry lock. */
    @Transactional(readOnly = true)
    public void assertAccessValid(String userId) {
        if (!isCustomer(userId)) return;
        CustomerEntitlement ent = requireEntitlement(userId);
        if (ent.isExpired()) {
            throw new AccessExpiredException(
                    "Your access has ended. Ask your retailer for a new access code.");
        }
    }

    /** Guard for creating a NEW project: expiry + allowance. */
    @Transactional(readOnly = true)
    public void assertCanCreateProject(String userId) {
        if (!isCustomer(userId)) return;
        CustomerEntitlement ent = requireEntitlement(userId);
        if (ent.isExpired()) {
            throw new AccessExpiredException(
                    "Your access has ended. Ask your retailer for a new access code.");
        }
        if (ent.getProjectsCreated() >= ent.getProjectAllowance()) {
            throw new QuotaExceededException(
                    "You've used your included project. Pay once for another, or ask your retailer to add one.");
        }
    }

    /** Record a created project (monotonic — deleting a project does not refund the slot). */
    @Transactional
    public void recordProjectCreated(String userId) {
        if (!isCustomer(userId)) return;
        entitlementRepository.findByCustomerId(userId).ifPresent(ent -> {
            ent.setProjectsCreated(ent.getProjectsCreated() + 1);
            entitlementRepository.save(ent);
        });
    }

    /** The customer's own status (for the UI to show remaining projects / expiry). Null if none. */
    @Transactional(readOnly = true)
    public CustomerEntitlementResponse getMyEntitlement(String userId) {
        return entitlementRepository.findByCustomerId(userId)
                .map(CustomerEntitlementResponse::from)
                .orElse(null);
    }

    /** Retailer: list the customers (and their entitlement) onboarded by an org. */
    @Transactional(readOnly = true)
    public List<CustomerEntitlementResponse> listCustomers(String requestingUserId, String retailerOrgId) {
        requireOwnerOrManager(requestingUserId, retailerOrgId);
        return entitlementRepository.findByRetailerOrgIdOrderByUpdatedAtDesc(retailerOrgId).stream()
                .map(CustomerEntitlementResponse::from)
                .toList();
    }

    /** Retailer: grant one more project to a customer they manage. */
    @Transactional
    public CustomerEntitlementResponse grantExtraProject(String requestingUserId, String retailerOrgId, String customerUserId) {
        requireOwnerOrManager(requestingUserId, retailerOrgId);
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer entitlement not found"));
        if (ent.getRetailerOrg() == null || !ent.getRetailerOrg().getId().equals(retailerOrgId)) {
            throw new SecurityException("This customer is not managed by your organization.");
        }
        ent.setProjectAllowance(ent.getProjectAllowance() + 1);
        entitlementRepository.save(ent);
        log.info("Retailer org {} granted +1 project to customer {} (allowance now {})",
                retailerOrgId, customerUserId, ent.getProjectAllowance());
        return CustomerEntitlementResponse.from(ent);
    }

    /** Credit one project after a verified one-time payment by the customer. */
    @Transactional
    public CustomerEntitlementResponse creditPurchasedProject(String userId) {
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active entitlement to credit. Redeem a retailer access code first."));
        ent.setProjectAllowance(ent.getProjectAllowance() + 1);
        entitlementRepository.save(ent);
        log.info("Credited +1 project to customer {} after payment (allowance now {})",
                userId, ent.getProjectAllowance());
        return CustomerEntitlementResponse.from(ent);
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
