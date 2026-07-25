package com.gridstore.huevista.account;

import com.gridstore.huevista.account.dto.CreateOrgRequest;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.dto.RedeemCodeRequest;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.razorpay.RazorpayClient;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AccountIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SubscriptionRepository subscriptionRepository;

    private String retailerOwnerToken;
    private String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        retailerOwnerToken = registerAndLogin("retailer-owner@example.com", "Retailer Owner",
                com.gridstore.huevista.auth.model.UserRole.RETAILER);
        // Walk-ins are CUSTOMER (the only role allowed to redeem an access code).
        customerToken = registerAndLogin("customer-walkin@example.com", "Walk-in Customer",
                com.gridstore.huevista.auth.model.UserRole.CUSTOMER);
    }

    @Test
    void createOrg_andSeeMine() throws Exception {
        CreateOrgRequest req = new CreateOrgRequest();
        req.setName("Sharda Paints");
        req.setSlug("sharda-paints");
        req.setType(OrgType.RETAILER);

        mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.slug").value("sharda-paints"))
                .andExpect(jsonPath("$.type").value("RETAILER"));

        mockMvc.perform(get("/api/organizations/mine")
                        .header("Authorization", "Bearer " + retailerOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slug").value("sharda-paints"));
    }

    @Test
    void duplicateSlug_returns4xx() throws Exception {
        createOrg(retailerOwnerToken, "Sharda", "sharda-paints", OrgType.RETAILER);

        CreateOrgRequest dup = new CreateOrgRequest();
        dup.setName("Another");
        dup.setSlug("sharda-paints");
        dup.setType(OrgType.RETAILER);

        mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dup)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticatedCreate_blocked() throws Exception {
        CreateOrgRequest req = new CreateOrgRequest();
        req.setName("Anon");
        req.setSlug("anon-shop");
        req.setType(OrgType.RETAILER);

        mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void accessCodeLifecycle_generateAndRedeem() throws Exception {
        String retailerOrgId = createOrg(retailerOwnerToken, "Sharda", "sharda-paints", OrgType.RETAILER);
        // Generating a code now charges the assigned projects against the retailer
        // owner's monthly image quota, so they need an active subscription.
        seedActiveSubscription("retailer-owner@example.com");

        GenerateAccessCodeRequest gen = new GenerateAccessCodeRequest();
        gen.setCustomerName("Walk-in Customer");
        gen.setProjectQuota(2);

        MvcResult genResult = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", retailerOrgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        String code = objectMapper.readTree(genResult.getResponse().getContentAsString()).get("code").asText();

        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode(code);

        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk());

        // Second redeem should fail
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void redeemAccount_createsSignedInCustomer_andChargesImageQuota() throws Exception {
        String retailerOrgId = createOrg(retailerOwnerToken, "Sharda", "sharda-paints", OrgType.RETAILER);
        seedActiveSubscription("retailer-owner@example.com");

        GenerateAccessCodeRequest gen = new GenerateAccessCodeRequest();
        gen.setCustomerName("Priya Sharma");
        gen.setProjectQuota(3);

        MvcResult genResult = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", retailerOrgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Priya Sharma"))
                .andExpect(jsonPath("$.projectQuota").value(3))
                .andExpect(jsonPath("$.validDays").value(10))
                .andReturn();
        String code = objectMapper.readTree(genResult.getResponse().getContentAsString()).get("code").asText();

        // The 3 assigned projects were charged to the owner's monthly image quota.
        User owner = userRepository.findByEmail("retailer-owner@example.com").orElseThrow();
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(owner.getId(), SubscriptionStatus.ACTIVE)
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(sub.getAiGenerationsUsed()).isEqualTo(3);

        // Redeeming needs NO auth — it auto-creates a signed-in CUSTOMER account.
        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/access-codes/redeem-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.name").value("Priya Sharma"))
                .andExpect(jsonPath("$.shopName").value("Sharda"))
                .andExpect(jsonPath("$.validDays").value(10));
    }

    @Test
    void redeemUnknownCode_returns4xx() throws Exception {
        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode("NOTREAL1");

        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());
    }

    // ── helpers ──

    /** Give a user an ACTIVE subscription with image quota, so they can assign projects. */
    private void seedActiveSubscription(String email) {
        User owner = userRepository.findByEmail(email).orElseThrow();
        subscriptionRepository.save(Subscription.builder()
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .aiGenerationsLimit(60)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build());
    }

    private String createOrg(String token, String name, String slug, OrgType type) throws Exception {
        CreateOrgRequest req = new CreateOrgRequest();
        req.setName(name); req.setSlug(slug); req.setType(type);
        MvcResult result = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String registerAndLogin(String email, String name,
                                    com.gridstore.huevista.auth.model.UserRole role) throws Exception {
        userRepository.save(User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .role(role)
                .build());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse authResp = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class);
        return authResp.getAccessToken();
    }
}
