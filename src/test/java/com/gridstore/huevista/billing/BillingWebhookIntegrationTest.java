package com.gridstore.huevista.billing;

import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
@TestPropertySource(locations = "classpath:application-test.properties")
class BillingWebhookIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired UserRepository userRepository;

    private Subscription testSubscription;

    @BeforeEach
    void setUp() {
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
                .aiGenerationsLimit(Plan.STARTER.getMonthlyAiLimit())
                .build());
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        Subscription updated = subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
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
        testSubscription.setAiGenerationsUsed(15);
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        Subscription updated = subscriptionRepository.findById(testSubscription.getId()).orElseThrow();
        assertThat(updated.getAiGenerationsUsed()).isEqualTo(0);
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));
    }
}
