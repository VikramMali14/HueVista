package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.SubscriptionRequiredException;
import com.gridstore.huevista.common.exception.VerificationRequiredException;
import com.gridstore.huevista.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project-creation policy for RETAILER accounts (the signup → trial →
 * subscription funnel). CUSTOMER accounts are governed separately by
 * {@link com.gridstore.huevista.account.service.CustomerEntitlementService}
 * (redeemed access codes), so they are intentionally skipped here.
 *
 * Rules:
 *  1. Email AND mobile must be verified before creating any project.
 *  2. An active subscription is required — the free trial counts as active.
 *  3. The free trial includes exactly ONE project; a second one requires an
 *     active PAID plan subscription.
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessPolicy {

    /** Projects a retailer may create while on the free trial. */
    static final int TRIAL_PROJECT_LIMIT = 1;

    private final SubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public void assertCanCreateProject(User user) {
        // Only the retailer funnel is gated here.
        if (user.getRole() != UserRole.RETAILER) {
            return;
        }

        // 1) Verification hard-gate (email + mobile).
        if (!user.isEmailVerified() || !user.isPhoneVerified()) {
            throw new VerificationRequiredException(
                    "Verify your email and mobile number before creating a project.");
        }

        // 2) Must have an active subscription — the trial granted at signup counts.
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new SubscriptionRequiredException(
                        "Your trial has ended. Subscribe to a plan to create projects."));

        // 3) The free trial includes exactly one project; more require a paid plan.
        //    Counts live projects (one-at-a-time during the trial); the monthly AI
        //    render quota remains the hard cost ceiling on either path.
        if (sub.isTrial()) {
            long created = projectRepository.countByUserId(user.getId());
            if (created >= TRIAL_PROJECT_LIMIT) {
                throw new SubscriptionRequiredException(
                        "Your free trial includes one project. Subscribe to a plan to create more.");
            }
        }
        // Paid subscription → allowed. (A per-plan project cap with one-time
        // top-ups once it's reached is a planned follow-on.)
    }
}
