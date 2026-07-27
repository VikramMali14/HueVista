package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.SubscriptionRequiredException;
import com.gridstore.huevista.common.exception.VerificationRequiredException;
import com.gridstore.huevista.project.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project-creation policy for RETAILER accounts (the signup → trial →
 * subscription funnel). CUSTOMER accounts are governed separately by
 * {@link com.gridstore.huevista.account.service.CustomerEntitlementService}
 * (redeemed access codes), so they are intentionally skipped here.
 *
 * Rules:
 *  1. Email / mobile must be verified before creating any project — but ONLY the
 *     channels this deployment can actually deliver a code on. Requiring a phone
 *     OTP while {@code app.sms.enabled=false} (codes go to the server log) would
 *     hard-block every retailer with a gate they can never pass; same for email
 *     when {@code app.mail.enabled=false}. Production turns both flags on and
 *     gets the full gate; a bare environment degrades to no verification gate
 *     rather than a deadlock.
 *  2. An active subscription is required — the free trial counts as active —
 *     UNLESS the retailer has bought a project outright. A standalone project
 *     purchase is exactly the escape hatch for a shop whose plan has lapsed:
 *     refusing it here would have sold them something they then could not use.
 *  3. The free trial includes as many projects as its plan's image quota — three
 *     on the free tier. Past that it takes a paid plan, or a bought project.
 */
@Service
public class ProjectAccessPolicy {

    private final SubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;
    private final boolean requireEmailVerified;
    private final boolean requirePhoneVerified;

    public ProjectAccessPolicy(
            SubscriptionRepository subscriptionRepository,
            ProjectRepository projectRepository,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${app.sms.enabled:false}") boolean smsEnabled) {
        this.subscriptionRepository = subscriptionRepository;
        this.projectRepository = projectRepository;
        this.requireEmailVerified = mailEnabled;
        this.requirePhoneVerified = smsEnabled;
    }

    @Transactional(readOnly = true)
    public void assertCanCreateProject(User user) {
        assertCanCreateProject(user, false);
    }

    /**
     * @param holdsPurchasedProject the caller has already claimed a paid project credit
     *        for this creation, so the subscription and trial-allowance gates are
     *        satisfied by the payment itself. Verification is still required — a shop
     *        we cannot reach is a shop we cannot support, whoever paid.
     */
    @Transactional(readOnly = true)
    public void assertCanCreateProject(User user, boolean holdsPurchasedProject) {
        // Only the retailer funnel is gated here.
        if (user.getRole() != UserRole.RETAILER) {
            return;
        }

        // 1) Verification hard-gate — only on channels that can actually deliver a code.
        boolean emailMissing = requireEmailVerified && !user.isEmailVerified();
        boolean phoneMissing = requirePhoneVerified && !user.isPhoneVerified();
        if (emailMissing || phoneMissing) {
            String what = emailMissing && phoneMissing ? "email and mobile number"
                    : emailMissing ? "email" : "mobile number";
            throw new VerificationRequiredException(
                    "Verify your " + what + " before creating a project.");
        }

        // 2) Must have an active subscription — the trial granted at signup counts —
        //    or a project they have already paid for outright.
        Subscription sub = subscriptionRepository
                .findEntitling(user.getId(), SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
                        java.time.LocalDateTime.now())
                .stream().findFirst()
                .orElse(null);
        if (sub == null) {
            if (holdsPurchasedProject) {
                return; // paid for; nothing left to check
            }
            throw new SubscriptionRequiredException(
                    "Your trial has ended. Subscribe to a plan, or buy a single project, to keep going.");
        }
        if (holdsPurchasedProject) {
            return; // an extra project bought on top of a plan bypasses the trial allowance
        }

        // 3) The trial's project allowance IS its image quota — three on the free tier —
        //    rather than a second constant that can drift from the plan it describes.
        //    Claimed against a MONOTONIC counter on the subscription, not a live count of
        //    rows: counting live projects meant deleting the trial project handed the slot
        //    straight back, so a trial account could create unlimited projects one at a
        //    time while the message promised a fixed number. The conditional UPDATE also
        //    makes parallel creates safe.
        int trialProjects = Math.max(1, sub.getAiGenerationsLimit());
        if (sub.isTrial()
                && subscriptionRepository.claimTrialProjectSlot(sub.getId(), trialProjects) == 0) {
            throw new SubscriptionRequiredException(
                    "Your free trial includes " + trialProjects + " project"
                    + (trialProjects == 1 ? "" : "s")
                    + ". Subscribe to a plan to create more, or buy a single project.");
        }
        // Paid subscription → allowed. (A per-plan project cap with one-time
        // top-ups once it's reached is a planned follow-on.)
    }
}
