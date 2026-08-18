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
    @Autowired com.gridstore.huevista.account.repository.CustomerEntitlementRepository entitlementRepository;

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
                // An absolute date, not a "valid for N days" count. Per-code validity went
                // when a code became something an account holds: an UNREDEEMED code lapses
                // (30 days), and once redeemed the customer's access never does.
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        String code = objectMapper.readTree(genResult.getResponse().getContentAsString()).get("code").asText();

        // The 3 assigned projects are HELD against the owner's monthly image quota — not
        // spent. They move into projectsUsed one at a time as the customer actually
        // renders each room, and come back if the code is revoked or expires unredeemed.
        // (Charging them straight to projectsUsed double-billed the shop, because the
        // render itself charged again.)
        User owner = userRepository.findByEmail("retailer-owner@example.com").orElseThrow();
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(owner.getId(), SubscriptionStatus.ACTIVE)
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(sub.getReservedProjects()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(sub.getProjectsUsed()).isZero();

        // Redeeming needs a signed-in CUSTOMER. A code cannot mint an account any more and
        // cannot be held by a browser: the shop issues it, the customer signs in, and the
        // code is added to the account they are already in.
        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(true))
                .andExpect(jsonPath("$.organizationName").value("Sharda"))
                .andExpect(jsonPath("$.projectQuota").value(3));

        // The customer now holds the three projects the shop paid for.
        mockMvc.perform(get("/api/me/entitlement")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectAllowance").value(3));
    }

    /**
     * A code is spent the moment it is claimed, and a second attempt is refused.
     *
     * <p>This replaces a test about RE-ENTRY: redemption used to mint a passwordless
     * account, so typing the code again was the customer's only way back to it, and
     * re-redeeming had to repair an entitlement that had gone missing. A code is added to
     * an account the customer is already signed into now — they get back in by signing in
     * — so the rule that matters is the opposite one: claiming is once, and the projects
     * belong to whoever claimed first.
     */
    @Test
    void aCodeCannotBeRedeemedTwice() throws Exception {
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
                .andReturn();
        String code = objectMapper.readTree(genResult.getResponse().getContentAsString()).get("code").asText();

        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk());

        // The same account, asking again: already used, and the allowance does not double.
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/me/entitlement").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectAllowance").value(3));

        // And a different customer cannot take a code somebody already claimed.
        String otherToken = registerAndLogin("second-walkin@example.com", "Second Walk-in",
                com.gridstore.huevista.auth.model.UserRole.CUSTOMER);
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());
    }

    /** A customer with no entitlement at all owns no shop work, so listing their
     *  projects must answer an empty list — not the 403 that turned the dashboard
     *  into an error panel sitting next to a "redeem a code" banner. */
    @Test
    void listProjects_forCustomerWithoutEntitlement_isEmptyNotForbidden() throws Exception {
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** The same account asked the other question: is a shop behind me?
     *
     *  This endpoint's "no" is an EMPTY 200 — {@code ResponseEntity.ok(null)}, and Spring
     *  writes no body at all for a null one. It is not a 404 and not the JSON literal
     *  {@code null}, and the difference is load-bearing: the frontend hides the customer's
     *  "My products" tab and closes the page on it, so a 404 would read as "the backend
     *  broke" (leave the page open) rather than "this account has no shop" (close it).
     *  Asserting the empty string rather than merely 200 is the point of the test — the
     *  status alone passed while the body was being read as a shop that does not exist. */
    @Test
    void myEntitlement_forCustomerWithoutOne_isAnEmptyOkNotAnError() throws Exception {
        mockMvc.perform(get("/api/me/entitlement").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    /** The shop pays an image per assigned project, so the code list has to report
     *  the quota and what is left of it. */
    @Test
    void listCodes_reportsProjectQuotaAndRemainder() throws Exception {
        String retailerOrgId = createOrg(retailerOwnerToken, "Sharda", "sharda-paints", OrgType.RETAILER);
        seedActiveSubscription("retailer-owner@example.com");

        GenerateAccessCodeRequest gen = new GenerateAccessCodeRequest();
        gen.setCustomerName("Priya Sharma");
        gen.setProjectQuota(3);
        mockMvc.perform(post("/api/organizations/{orgId}/access-codes", retailerOrgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/organizations/{orgId}/access-codes", retailerOrgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].projectQuota").value(3))
                .andExpect(jsonPath("$[0].projectsUsed").value(0))
                .andExpect(jsonPath("$[0].projectsRemaining").value(3));
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
    @Test
    void revokingAnUnredeemedCode_killsTheCodeAndSaysSo() throws Exception {
        String orgId = createOrg(retailerOwnerToken, "Sharda", "sharda-paints", OrgType.RETAILER);
        seedActiveSubscription("retailer-owner@example.com");

        GenerateAccessCodeRequest gen = new GenerateAccessCodeRequest();
        gen.setCustomerName("Walk-in Customer");
        gen.setProjectQuota(3);
        MvcResult genResult = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", orgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated())
                .andReturn();
        var body = objectMapper.readTree(genResult.getResponse().getContentAsString());
        String codeId = body.get("id").asText();
        String code = body.get("code").asText();

        User owner = userRepository.findByEmail("retailer-owner@example.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(owner.getId(), SubscriptionStatus.ACTIVE)
                .orElseThrow().getReservedProjects()).isEqualTo(3);

        // Cancelling stops anyone redeeming the code. It does NOT hand the quota back:
        // the projects on a code are bought when it is issued and they are spent, which is
        // what removed the reservation ledger that used to move them back and forth.
        //
        // The response must SAY it revoked the code. It used to answer `revoked: false`
        // straight after revoking one — the guarded UPDATE goes to the database, and the
        // re-read that builds this response was served the row the persistence context had
        // cached a moment earlier. See CustomerAccessCodeRepository#revokeIfUnused.
        mockMvc.perform(delete("/api/organizations/{orgId}/access-codes/{codeId}", orgId, codeId)
                        .header("Authorization", "Bearer " + retailerOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        org.assertj.core.api.Assertions.assertThat(subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(owner.getId(), SubscriptionStatus.ACTIVE)
                .orElseThrow().getReservedProjects()).isEqualTo(3);

        // …and the revoked code can never be redeemed.
        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void anAlreadyRedeemedCodeCannotBeRevoked() throws Exception {
        String orgId = createOrg(retailerOwnerToken, "Sharda", "sharda-paints", OrgType.RETAILER);
        seedActiveSubscription("retailer-owner@example.com");

        GenerateAccessCodeRequest gen = new GenerateAccessCodeRequest();
        gen.setCustomerName("Walk-in Customer");
        gen.setProjectQuota(1);
        MvcResult genResult = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", orgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated())
                .andReturn();
        var body = objectMapper.readTree(genResult.getResponse().getContentAsString());
        String codeId = body.get("id").asText();

        RedeemCodeRequest redeem = new RedeemCodeRequest();
        redeem.setCode(body.get("code").asText());
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk());

        // The customer may already have work under it — pulling access after the fact
        // would strand them mid-visit at the counter.
        mockMvc.perform(delete("/api/organizations/{orgId}/access-codes/{codeId}", orgId, codeId)
                        .header("Authorization", "Bearer " + retailerOwnerToken))
                .andExpect(status().is4xxClientError());
    }

    private void seedActiveSubscription(String email) {
        User owner = userRepository.findByEmail(email).orElseThrow();
        subscriptionRepository.save(Subscription.builder()
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .projectsLimit(60)
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
