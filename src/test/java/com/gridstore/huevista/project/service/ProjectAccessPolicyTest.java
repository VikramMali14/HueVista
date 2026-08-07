package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.SubscriptionRequiredException;
import com.gridstore.huevista.common.exception.VerificationRequiredException;
import com.gridstore.huevista.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAccessPolicyTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock ProjectRepository projectRepository;

    /** Full gate: both delivery channels configured (production shape). */
    private ProjectAccessPolicy fullGate() {
        return new ProjectAccessPolicy(subscriptionRepository, projectRepository, true, true);
    }

    private User retailer(boolean emailVerified, boolean phoneVerified) {
        return User.builder()
                .id("u1").email("r@example.com").name("Retailer")
                .role(UserRole.RETAILER)
                .emailVerified(emailVerified).phoneVerified(phoneVerified)
                .build();
    }

    private Subscription sub(Plan plan, boolean trial) {
        // A trial's project allowance IS its tier's monthly quota, so the limit has to
        // come off the subscription rather than a constant.
        return Subscription.builder()
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .trial(trial)
                .projectsLimit(plan.getMonthlyProjectLimit())
                .build();
    }

    /** A time-boxed trial, which since the free tier started renewing only ever runs on a
     *  PAID tier — the free plan is a standing subscription, not a trial. */
    private Subscription trialSub() {
        return sub(Plan.STARTER, true);
    }

    /** The plan a new shop is on: FREE, renewing, and NOT a trial. */
    private Subscription freeTierSub() {
        return sub(Plan.FREE, false);
    }

    /** The trial gate claims a slot via a conditional UPDATE now, so stub that instead
     *  of the old live-project COUNT (which let a deleted project free the slot). */
    private void trialSlotAvailable(boolean available) {
        when(subscriptionRepository.claimTrialProjectSlot(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(available ? 1 : 0);
    }

    private void activeSub(Subscription s) {
        when(subscriptionRepository.findEntitling(eq("u1"), eq(SubscriptionStatus.ACTIVE), eq(SubscriptionStatus.CANCELLED), any()))
                .thenReturn(s == null ? java.util.List.of() : java.util.List.of(s));
    }

    @Test
    void customers_are_not_gated_here() {
        User customer = User.builder().id("c1").email("c@example.com").name("Cust")
                .role(UserRole.CUSTOMER).emailVerified(false).phoneVerified(false).build();
        assertThatCode(() -> fullGate().assertCanCreateProject(customer)).doesNotThrowAnyException();
    }

    @Test
    void retailer_without_email_verified_is_blocked() {
        assertThatThrownBy(() -> fullGate().assertCanCreateProject(retailer(false, true)))
                .isInstanceOf(VerificationRequiredException.class)
                .hasMessageContaining("email");
    }

    @Test
    void retailer_without_phone_verified_is_blocked() {
        assertThatThrownBy(() -> fullGate().assertCanCreateProject(retailer(true, false)))
                .isInstanceOf(VerificationRequiredException.class)
                .hasMessageContaining("mobile");
    }

    @Test
    void phone_gate_is_skipped_when_sms_channel_is_not_configured() {
        // sms disabled -> a phone OTP can never reach the retailer, so the gate must
        // not demand it (previously this deadlocked every retailer at launch).
        ProjectAccessPolicy policy =
                new ProjectAccessPolicy(subscriptionRepository, projectRepository, true, false);
        activeSub(trialSub());
        trialSlotAvailable(true);
        assertThatCode(() -> policy.assertCanCreateProject(retailer(true, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void email_gate_is_skipped_when_mail_channel_is_not_configured() {
        ProjectAccessPolicy policy =
                new ProjectAccessPolicy(subscriptionRepository, projectRepository, false, true);
        activeSub(trialSub());
        trialSlotAvailable(true);
        assertThatCode(() -> policy.assertCanCreateProject(retailer(false, true)))
                .doesNotThrowAnyException();
    }

    @Test
    void no_channels_configured_skips_verification_but_still_requires_subscription() {
        ProjectAccessPolicy policy =
                new ProjectAccessPolicy(subscriptionRepository, projectRepository, false, false);
        activeSub(null);
        assertThatThrownBy(() -> policy.assertCanCreateProject(retailer(false, false)))
                .isInstanceOf(SubscriptionRequiredException.class);
    }

    @Test
    void verified_retailer_without_active_subscription_must_subscribe() {
        activeSub(null);
        assertThatThrownBy(() -> fullGate().assertCanCreateProject(retailer(true, true)))
                .isInstanceOf(SubscriptionRequiredException.class)
                .hasMessageContaining("Subscribe");
    }

    @Test
    void trial_retailer_can_create_a_project_within_the_tiers_allowance() {
        activeSub(trialSub());
        trialSlotAvailable(true);
        assertThatCode(() -> fullGate().assertCanCreateProject(retailer(true, true))).doesNotThrowAnyException();
    }

    @Test
    void trial_retailer_blocked_once_the_tiers_projects_are_used() {
        activeSub(trialSub());
        trialSlotAvailable(false);
        assertThatThrownBy(() -> fullGate().assertCanCreateProject(retailer(true, true)))
                .isInstanceOf(SubscriptionRequiredException.class)
                .hasMessageContaining("Your trial includes 15 projects");
    }

    /** The cap is read off the plan, so it moves with the tier instead of a constant. */
    @Test
    void theTrialCapIsTheTiersProjectQuota() {
        activeSub(trialSub());
        trialSlotAvailable(true);
        fullGate().assertCanCreateProject(retailer(true, true));
        verify(subscriptionRepository)
                .claimTrialProjectSlot(any(), eq(Plan.STARTER.getMonthlyProjectLimit()));
    }

    /**
     * The free tier is NOT policed here.
     *
     * Its two projects renew every month, so they are an ordinary monthly allowance and
     * belong to the monthly quota gate in BillingService, which resets with the cycle.
     * The counter behind claimTrialProjectSlot is monotonic by design and never comes
     * back — claiming against it would have capped every free shop at two projects for
     * the life of the account, whatever the calendar said.
     */
    @Test
    void free_tier_retailer_is_left_to_the_monthly_quota_gate() {
        activeSub(freeTierSub());
        assertThatCode(() -> fullGate().assertCanCreateProject(retailer(true, true)))
                .doesNotThrowAnyException();
        verify(subscriptionRepository, org.mockito.Mockito.never())
                .claimTrialProjectSlot(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void paid_retailer_is_not_limited_to_one_project() {
        activeSub(sub(Plan.PROFESSIONAL, false));
        // Paid path must not consult the project count at all.
        assertThatCode(() -> fullGate().assertCanCreateProject(retailer(true, true))).doesNotThrowAnyException();
    }
}
