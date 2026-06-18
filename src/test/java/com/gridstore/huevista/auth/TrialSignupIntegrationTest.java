package com.gridstore.huevista.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.dto.CreateRetailerRequest;
import com.gridstore.huevista.auth.dto.RegisterRequest;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
    @Autowired PasswordEncoder passwordEncoder;

    /** Shops are admin-created now: only ROLE_ADMIN can provision a RETAILER + org + trial. */
    @Test
    void admin_creating_a_retailer_provisions_org_and_trial() throws Exception {
        userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"root@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        String adminToken = objectMapper.readValue(
                login.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();

        CreateRetailerRequest req = new CreateRetailerRequest();
        req.setName("Shop Owner");
        req.setEmail("shop@example.com");
        req.setPassword("password123");
        req.setShopName("Sharda Paints");
        req.setCity("Belgavi");
        req.setState("Karnataka");
        req.setTier("pro");

        mockMvc.perform(post("/api/admin/retailers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        User retailer = userRepository.findByEmail("shop@example.com").orElseThrow();
        assertThat(retailer.getRole()).isEqualTo(UserRole.RETAILER);

        assertThat(organizationRepository.findAll())
                .anyMatch(o -> "Sharda Paints".equals(o.getName()) && o.getType() == OrgType.RETAILER);

        List<Subscription> subs = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(retailer.getId());
        assertThat(subs).hasSize(1);
        Subscription sub = subs.get(0);
        assertThat(sub.isTrial()).isTrue();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getPlan()).isEqualTo(Plan.PROFESSIONAL);
        assertThat(sub.getRazorpaySubscriptionId()).isNull();
        assertThat(sub.getAiGenerationsLimit()).isEqualTo(Plan.PROFESSIONAL.getMonthlyAiLimit());
    }

    /** Public signup creates a plain CUSTOMER — no shop, no subscription. */
    @Test
    void public_signup_creates_a_customer_with_no_subscription() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Plain User");
        reg.setEmail("plain@example.com");
        reg.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail("plain@example.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
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
