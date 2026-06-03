package com.gridstore.huevista.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.RegisterRequest;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class TrialSignupIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired SubscriptionRepository subscriptionRepository;

    @Test
    void retailer_trial_signup_provisions_org_and_trial_subscription() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Shop Owner");
        reg.setEmail("shop@example.com");
        reg.setPassword("password123");
        reg.setShopName("Sharda Paints");
        reg.setCity("Belgavi");
        reg.setState("Karnataka");
        reg.setTier("pro");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail("shop@example.com").orElseThrow();

        // A RETAILER org was provisioned from the shop name.
        assertThat(organizationRepository.findAll())
                .anyMatch(o -> "Sharda Paints".equals(o.getName()) && o.getType() == OrgType.RETAILER);

        // A free trial subscription (ACTIVE, no Razorpay id) was granted.
        List<Subscription> subs = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(subs).hasSize(1);
        Subscription sub = subs.get(0);
        assertThat(sub.isTrial()).isTrue();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getPlan()).isEqualTo(Plan.PROFESSIONAL);
        assertThat(sub.getRazorpaySubscriptionId()).isNull();
        assertThat(sub.getAiGenerationsLimit()).isEqualTo(Plan.PROFESSIONAL.getMonthlyAiLimit());
    }

    @Test
    void plain_signup_does_not_create_a_subscription() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Plain User");
        reg.setEmail("plain@example.com");
        reg.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail("plain@example.com").orElseThrow();
        assertThat(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    @Test
    void forgot_password_returns_200_for_unknown_email() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "nobody@example.com"))))
                .andExpect(status().isOk());
    }
}
