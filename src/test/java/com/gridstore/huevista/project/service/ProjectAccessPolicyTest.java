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

    private Subscription sub(boolean trial) {
        return Subscription.builder()
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .trial(trial)
                .build();
    }

    private void activeSub(Subscription s) {
        when(subscriptionRepository.findTopByUserIdAndStatusOrderByCreatedAtDesc("u1", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.ofNullable(s));
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
        activeSub(sub(true));
        when(projectRepository.countByUserId("u1")).thenReturn(0L);
        assertThatCode(() -> policy.assertCanCreateProject(retailer(true, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void email_gate_is_skipped_when_mail_channel_is_not_configured() {
        ProjectAccessPolicy policy =
                new ProjectAccessPolicy(subscriptionRepository, projectRepository, false, true);
        activeSub(sub(true));
        when(projectRepository.countByUserId("u1")).thenReturn(0L);
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
    void trial_retailer_can_create_first_project() {
        activeSub(sub(true));
        when(projectRepository.countByUserId("u1")).thenReturn(0L);
        assertThatCode(() -> fullGate().assertCanCreateProject(retailer(true, true))).doesNotThrowAnyException();
    }

    @Test
    void trial_retailer_blocked_after_one_project() {
        activeSub(sub(true));
        when(projectRepository.countByUserId("u1")).thenReturn(1L);
        assertThatThrownBy(() -> fullGate().assertCanCreateProject(retailer(true, true)))
                .isInstanceOf(SubscriptionRequiredException.class)
                .hasMessageContaining("free trial includes one project");
    }

    @Test
    void paid_retailer_is_not_limited_to_one_project() {
        activeSub(sub(false));
        // Paid path must not consult the project count at all.
        assertThatCode(() -> fullGate().assertCanCreateProject(retailer(true, true))).doesNotThrowAnyException();
    }
}
