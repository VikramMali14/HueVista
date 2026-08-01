package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limit-gated quota UPDATEs, run against a real database on an UNLIMITED plan.
 *
 * Enterprise stores {@link Integer#MAX_VALUE} as its monthly limit, and the allowance
 * those queries test against is {@code limit + purchased + carried}. Summed in the
 * columns' own integer type that overflows the moment the shop holds a single bought or
 * carried credit — and Postgres RAISES on int4 overflow ("integer out of range") rather
 * than wrapping, so the whole transaction aborted. In practice that meant an Enterprise
 * shop could not create a project, issue an access code, or grant a project to a
 * customer: every one of those paths runs one of these two statements.
 *
 * The Java-side arithmetic always widened to long
 * ({@link Subscription#effectiveProjectAllowance()}); only the SQL did not.
 *
 * These tests deliberately go through the REPOSITORY rather than a mock — the defect
 * lives in the generated SQL, so a mocked repository cannot see it. They assert
 * behaviour rather than the absence of an exception, which pins the bug on either engine:
 * where the addition raises, the call blows up; where it silently wraps to a large
 * negative, the predicate reads false and the charge is refused instead.
 */
@SpringBootTest
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class UnlimitedPlanQuotaOverflowTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired UserRepository userRepository;

    private String subId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .name("Unlimited Shop")
                .email("unlimited-shop@example.com")
                .password("x")
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .role(UserRole.RETAILER)
                .build());

        // An Enterprise row exactly as an admin grant leaves it: unlimited monthly
        // allowance, plus the credits carryOverCredits() moves across from the plan it
        // superseded. Both buckets are populated because both feed the same sum.
        Subscription sub = subscriptionRepository.save(Subscription.builder()
                .user(owner)
                .plan(Plan.ENTERPRISE)
                .status(SubscriptionStatus.ACTIVE)
                .projectsUsed(0)
                .reservedProjects(0)
                .projectsLimit(Integer.MAX_VALUE)
                .purchasedProjectCredits(5)
                .carriedProjectCredits(30)
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build());
        subId = sub.getId();
    }

    @Test
    void anUnlimitedPlanHoldingCreditsCanStillChargeAProject() {
        int charged = subscriptionRepository.incrementProjectUsageIfWithinLimit(subId);

        assertThat(charged).as("an unlimited plan must never refuse a project").isEqualTo(1);
        assertThat(subscriptionRepository.findById(subId).orElseThrow().getProjectsUsed())
                .isEqualTo(1);
    }

    @Test
    void anUnlimitedPlanHoldingCreditsCanStillHoldProjectsForAnAccessCode() {
        int held = subscriptionRepository.reserveProjectsIfWithinLimit(subId, 3);

        assertThat(held).as("an unlimited plan must never refuse a hold").isEqualTo(1);
        assertThat(subscriptionRepository.findById(subId).orElseThrow().getReservedProjects())
                .isEqualTo(3);
    }

    @Test
    void aFinitePlanStillRefusesOnceItsAllowanceIsSpent() {
        // The widening must not turn the gate off for everyone else: a real ceiling still
        // has to hold. 10 + 2 bought = 12, all of it spent.
        Subscription finite = subscriptionRepository.save(Subscription.builder()
                .user(userRepository.save(User.builder()
                        .name("Finite Shop").email("finite-shop@example.com").password("x")
                        .provider(AuthProvider.LOCAL).emailVerified(true)
                        .role(UserRole.RETAILER).build()))
                .plan(Plan.STARTER)
                .status(SubscriptionStatus.ACTIVE)
                .projectsUsed(12)
                .reservedProjects(0)
                .projectsLimit(10)
                .purchasedProjectCredits(2)
                .carriedProjectCredits(0)
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build());

        assertThat(subscriptionRepository.incrementProjectUsageIfWithinLimit(finite.getId()))
                .isZero();
        assertThat(subscriptionRepository.reserveProjectsIfWithinLimit(finite.getId(), 1))
                .isZero();
    }
}
