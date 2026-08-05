package com.gridstore.huevista.billing;

import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;

import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.model.AuthProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// A non-blank webhook secret so the service doesn't (correctly) fail closed; the
// HMAC check itself is stubbed below so the test isn't coupled to the SDK's exact
// signature encoding — it verifies event handling, not the crypto primitive.
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = "razorpay.webhook-secret=test_whsec_huevista")
class BillingWebhookIntegrationTest {

    private static final String SIGNATURE = "stubbed-signature";

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private Subscription testSubscription;
    private MockedStatic<Utils> utilsMock;

    @BeforeEach
    void setUp() {
        // Treat any presented signature as valid (constant-true). Razorpay's real
        // HMAC verification is exercised by the SDK's own tests; here we only care
        // that a verified webhook drives the correct subscription state transitions.
        utilsMock = Mockito.mockStatic(Utils.class);
        utilsMock.when(() -> Utils.verifyWebhookSignature(anyString(), anyString(), anyString()))
                .thenReturn(true);

        User user = userRepository.save(User.builder()
                .name("Webhook User")
                .email("webhook@example.com")
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .build());

        testSubscription = subscriptionRepository.save(Subscription.builder()
                .user(user)
                .plan(Plan.STARTER)
                .status(SubscriptionStatus.CREATED)
                .razorpaySubscriptionId("sub_test_123")
                .projectsLimit(Plan.STARTER.getMonthlyProjectLimit())
                .build());
    }

    @AfterEach
    void tearDown() {
        utilsMock.close();
    }

    @Test
    void webhook_subscription_activated_transitions_to_active() throws Exception {
        String payload = """
                {
                  "event": "subscription.activated",
                  "payload": {
                    "subscription": {
                      "entity": {
                        "id": "sub_test_123",
                        "charge_at": 1700000000,
                        "current_end": 1702592000
                      }
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        Subscription updated = subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    /**
     * The SECOND plan a shop buys — the first one that has something to supersede.
     *
     * Carrying the old plan's unused allowance across runs a bulk UPDATE declared
     * {@code clearAutomatically}, which detaches the subscription being activated. The
     * confirmation email then dereferenced its now-detached lazy {@code user} proxy and
     * threw LazyInitializationException — inside {@code @Transactional}, so the whole
     * activation ROLLED BACK after the card had already been charged, the controller
     * answered 500, and Razorpay retried the event into the same failure again and again.
     *
     * The flush + clear is what makes this reproduce at all: without it the handler reuses
     * the User this fixture just persisted, which is a real initialized instance rather
     * than the proxy a fresh load produces in production. That is precisely why the
     * existing activation test above stayed green through the bug.
     */
    @Test
    void webhook_activation_survives_superseding_an_existing_plan() throws Exception {
        User user = userRepository.findById(testSubscription.getUser().getId()).orElseThrow();
        // The free trial being upgraded away from, with its allowance unspent so the
        // carry-over — and the context-clearing UPDATE behind it — actually runs.
        subscriptionRepository.save(Subscription.builder()
                .user(user)
                .plan(Plan.STARTER)
                .status(SubscriptionStatus.ACTIVE)
                .trial(true)
                .projectsLimit(Plan.STARTER.getMonthlyProjectLimit())
                .projectsUsed(0)
                .currentPeriodStart(LocalDateTime.now().minusDays(3))
                .currentPeriodEnd(LocalDateTime.now().plusDays(4))
                .build());
        entityManager.flush();
        entityManager.clear();

        String payload = """
                {
                  "event": "subscription.activated",
                  "payload": {
                    "subscription": {
                      "entity": {
                        "id": "sub_test_123",
                        "charge_at": 1700000000,
                        "current_end": 1702592000
                      }
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        // Committed, not rolled back: the plan the shop paid for is actually live, and it
        // inherited the trial's unspent projects.
        Subscription activated =
                subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(activated.getCarriedProjectCredits())
                .isEqualTo(Plan.STARTER.getMonthlyProjectLimit());
    }

    @Test
    void webhook_subscription_halted_transitions_to_halted() throws Exception {
        String payload = """
                {
                  "event": "subscription.halted",
                  "payload": {
                    "subscription": {
                      "entity": {
                        "id": "sub_test_123"
                      }
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        Subscription updated = subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.HALTED);
    }

    @Test
    void webhook_payment_captured_resets_ai_usage() throws Exception {
        // Set some usage first
        testSubscription.setStatus(SubscriptionStatus.ACTIVE);
        testSubscription.setProjectsUsed(15);
        subscriptionRepository.save(testSubscription);

        String payload = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_test_456",
                        "subscription_id": "sub_test_123",
                        "amount": 49900
                      }
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        Subscription updated = subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getProjectsUsed()).isEqualTo(0);
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    /**
     * The two kinds of extra credit expire differently, and renewal is where that shows.
     *
     * Projects CARRIED from a plan the shop upgraded away from are someone's unused
     * monthly allowance: they were moved across so upgrading mid-cycle didn't forfeit
     * them, and they last the cycle they were carried into. Projects BOUGHT at the plan's
     * rate are money, and never evaporate. Renewing must therefore clear one and keep the
     * other — clearing both would take back something the shop paid for, and keeping both
     * would make a one-cycle grace period permanent.
     */
    @Test
    void webhook_payment_captured_expires_carried_projects_but_keeps_purchased_ones() throws Exception {
        testSubscription.setStatus(SubscriptionStatus.ACTIVE);
        testSubscription.setProjectsUsed(9);
        testSubscription.setCarriedProjectCredits(5);
        testSubscription.setPurchasedProjectCredits(4);
        // A RENEWAL, which is what this test is about: cycle 0 is the charge that starts
        // the plan. The fixture used to leave this at 0, so it asserted the expiry against
        // a FIRST charge — see the test below for why that distinction matters.
        testSubscription.setBillingCyclesCharged(1);
        subscriptionRepository.save(testSubscription);

        String payload = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_test_789",
                        "subscription_id": "sub_test_123",
                        "amount": 49900
                      }
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        Subscription updated = subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getCarriedProjectCredits()).isZero();
        assertThat(updated.getPurchasedProjectCredits()).isEqualTo(4);
        // The allowance is rebuilt from the plan rather than only ever raised, so a tier
        // whose quota changes reaches the shops already on it.
        assertThat(updated.getProjectsLimit())
                .isEqualTo(Plan.STARTER.getMonthlyProjectLimit());
    }

    /**
     * The charge that STARTS a plan must not expire the allowance the upgrade just moved
     * onto it.
     *
     * An immediate mid-cycle upgrade carries the old plan's unused projects across during
     * activation — the checkout-verify path, seconds before Razorpay delivers the first
     * subscription.charged. That charge is cycle 0, not a renewal, but the handler expired
     * carried credits on every charge, so the leftover the shop had just been given was
     * destroyed almost immediately. The Terms promise the opposite: what is left of the old
     * plan's allowance "moves onto the new one and lasts until that cycle renews".
     *
     * Purchased credits are checked here too. They are money and never expire, so this also
     * pins down that skipping the expiry on cycle 0 did not quietly make them mortal.
     */
    @Test
    void webhook_first_charge_keeps_projects_carried_in_by_an_upgrade() throws Exception {
        // Exactly the state the verify path leaves behind for an immediate upgrade: live,
        // never charged, holding the allowance carried off the plan it replaced.
        testSubscription.setStatus(SubscriptionStatus.ACTIVE);
        testSubscription.setCarriedProjectCredits(2);
        testSubscription.setPurchasedProjectCredits(3);
        testSubscription.setBillingCyclesCharged(0);
        subscriptionRepository.save(testSubscription);

        String payload = """
                {
                  "event": "subscription.charged",
                  "payload": {
                    "subscription": {
                      "entity": {
                        "id": "sub_test_123",
                        "current_end": 1702592000
                      }
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        Subscription updated =
                subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getCarriedProjectCredits()).isEqualTo(2);
        assertThat(updated.getPurchasedProjectCredits()).isEqualTo(3);
        // Still counted as the plan's first charge having happened, so the NEXT one renews.
        assertThat(updated.getBillingCyclesCharged()).isEqualTo(1);
    }

    /**
     * One charge, delivered as two events, must be applied once.
     *
     * docs/RAZORPAY_SETUP.md has merchants tick both subscription.charged AND
     * payment.captured, and Razorpay sends both for the same renewal. They are DIFFERENT
     * events with different ids, so the event-id replay guard lets both through by design
     * — it exists to stop a redelivery of one event, not two events describing one charge.
     *
     * Applying that charge twice counted two billing cycles for one payment, and the
     * second pass — no longer the "first" charge — expired the projects an upgrade had
     * just carried across, which is the very thing the cycle-0 check above protects.
     */
    @Test
    void webhook_one_charge_delivered_as_two_events_is_applied_once() throws Exception {
        testSubscription.setStatus(SubscriptionStatus.ACTIVE);
        testSubscription.setCarriedProjectCredits(2);
        testSubscription.setBillingCyclesCharged(0);
        subscriptionRepository.save(testSubscription);

        // The authoritative renewal event carries the payment alongside the subscription.
        String charged = """
                {
                  "event": "subscription.charged",
                  "payload": {
                    "subscription": {
                      "entity": { "id": "sub_test_123", "current_end": 1702592000 }
                    },
                    "payment": { "entity": { "id": "pay_cycle_one" } }
                  }
                }
                """;
        // …and the fallback describes the same money a second time.
        String captured = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": { "id": "pay_cycle_one", "subscription_id": "sub_test_123" }
                    }
                  }
                }
                """;

        for (String payload : new String[] { charged, captured }) {
            mockMvc.perform(post("/api/billing/webhooks/razorpay")
                            .header("X-Razorpay-Signature", SIGNATURE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk());
        }

        Subscription updated =
                subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getBillingCyclesCharged()).isEqualTo(1);
        assertThat(updated.getCarriedProjectCredits()).isEqualTo(2);
    }

    @Test
    void webhook_unknown_event_returns_processed() throws Exception {
        String payload = """
                {
                  "event": "some.unknown.event",
                  "payload": {}
                }
                """;

        mockMvc.perform(post("/api/billing/webhooks/razorpay")
                        .header("X-Razorpay-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));
    }
}
