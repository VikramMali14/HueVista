package com.gridstore.huevista.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.dto.CreateOrgRequest;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.dto.GrantCodeProjectsRequest;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Topping up a code the customer already holds: more projects on it, or another 10 days.
 *
 * Both exist for the same counter moment — the customer is standing there and wants one
 * more room, or came back on day 11 — and both are gated on the shop having a live plan,
 * because the projects behind a code are reserved against the shop's monthly image quota
 * and there is nothing to reserve against without one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AccessCodeTopUpIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired CustomerAccessCodeRepository codeRepository;
    @Autowired com.gridstore.huevista.account.service.GuestAccountService guestAccountService;
    @Autowired CustomerEntitlementRepository entitlementRepository;
    @Autowired com.gridstore.huevista.account.service.AccessCodeService accessCodeService;
    @Autowired com.gridstore.huevista.billing.service.ProjectCreditLedger projectCreditLedger;

    private static final String SHOP_EMAIL = "topup-shop@example.com";

    private String shopToken;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        shopToken = registerAndLogin(SHOP_EMAIL, "Top-up Shop", UserRole.RETAILER);
        orgId = createOrg(shopToken, "Top-up Paints", "topup-paints");
        seedSubscription(SHOP_EMAIL, SubscriptionStatus.ACTIVE);
    }



    /**
     * The customer's own allowance is what actually gates their rooms. Moving only the
     * code's quota would leave them holding a code that promises five projects and an
     * entitlement that refuses the third.
     */
    @Test
    void grantingMoreProjectsAlsoRaisesTheRedeemingCustomersAllowance() throws Exception {
        JsonNode code = generateCode(1);
        String customerId = redeemOntoASignedInCustomer(code.get("code").asText());
        assertThat(entitlementRepository.findByCustomerId(customerId).orElseThrow()
                .getProjectAllowance()).isEqualTo(1);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects",
                        orgId, code.get("id").asText())
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(2))))
                .andExpect(status().isOk());

        assertThat(entitlementRepository.findByCustomerId(customerId).orElseThrow()
                .getProjectAllowance()).isEqualTo(3);
    }

    // Extension is gone, and with it the two tests that pinned its 10-day reset and its
    // day-11 revival. A code's validity is no longer something a shop manages: an
    // UNREDEEMED code lapses 30 days after it is issued, and a REDEEMED one never lapses
    // at all, because the projects on it belong to the customer from the moment they claim
    // them. There is no window left to extend. See the commit that made an access code
    // something an account holds.

    /** Leave the plan with exactly enough for the one project already held by a code. */
    private void spendAllButTheHeldProject(String shopUserId) {
        Subscription sub = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(shopUserId)
                .stream().findFirst().orElseThrow();
        sub.setProjectsUsed(sub.getProjectsLimit() - sub.getReservedProjects());
        subscriptionRepository.saveAndFlush(sub);
    }

    /** A shop whose plan has lapsed cannot put more projects on a code it already issued. */
    @Test
    void aLapsedShopCannotGrantMoreProjects() throws Exception {
        JsonNode code = generateCode(1);
        String codeId = code.get("id").asText();
        expireSubscription(SHOP_EMAIL);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(1))))
                .andExpect(status().isPaymentRequired());
    }

    /** A cancelled code is dead: its held quota already went back to the shop. */
    @Test
    void aCancelledCodeCannotBeToppedUp() throws Exception {
        JsonNode code = generateCode(1);
        String codeId = code.get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/organizations/{orgId}/access-codes/{codeId}", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(1))))
                .andExpect(status().is4xxClientError());
    }

    /**
     * An account with no reachable address carries a synthesised one — so nothing
     * user-facing may present it as somewhere to write.
     */
    @Test
    void aGuestAccountReportsNoEmailAnywhereUserFacing() throws Exception {
        // Redeeming a code cannot mint an account any more, so the account with no real
        // address is the one the KIOSK opens for a walk-in who declined to give one. Its
        // stored address is synthesised from the code purely to key the row.
        JsonNode code = generateCode(1);
        var stored = codeRepository.findByCode(code.get("code").asText()).orElseThrow();
        var guest = guestAccountService.provisionForKiosk(stored, null, null);

        assertThat(guest.getEmail()).endsWith(com.gridstore.huevista.auth.util.Emails.SYNTHETIC_DOMAIN);
        assertThat(com.gridstore.huevista.auth.util.Emails.publicEmailOf(guest)).isNull();

        // The shop's own customer list shows the name on the code, never the placeholder —
        // a shop reading one would take it for somewhere they could write.
        mockMvc.perform(get("/api/organizations/{orgId}/customers", orgId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerEmail").doesNotExist());
    }

    /**
     * The {@code {orgId}} in the URL has to match the code's own shop.
     *
     * These endpoints authorise on the code's organization, which is what makes them
     * safe — but the path segment was simply ignored, so a member of two shops could
     * reach one shop's code through the other shop's URL and act on it. Not exploitable
     * as it stood; a URL that lies is a trap for whoever changes this next.
     */
    @Test
    void aCodeCannotBeReachedThroughAnotherShopsUrl() throws Exception {
        JsonNode code = generateCode(1);
        String codeId = code.get("id").asText();
        String otherOrgId = createOrg(shopToken, "Top-up Paints Two", "topup-paints-two");

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects",
                        otherOrgId, codeId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(1))))
                .andExpect(status().isNotFound());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/organizations/{orgId}/access-codes/{codeId}", otherOrgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isNotFound());

        // …and still works through its own shop's URL.
        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(1))))
                .andExpect(status().isOk());
    }

    private int heldImagesFor(String userId) {
        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().map(Subscription::getReservedProjects).orElse(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode generateCode(int projectQuota) throws Exception {
        GenerateAccessCodeRequest req = new GenerateAccessCodeRequest();
        req.setCustomerName("Walk-in Customer");
        req.setProjectQuota(projectQuota);
        MvcResult result = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", orgId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Redeems into a fresh passwordless account and returns its user id. */
    /**
     * A customer who signs in and claims {@code code}, and their user id.
     *
     * <p>Named for what it used to do: POST the code and get back a brand-new account with
     * a session on it. A code cannot mint an account any more — it is added to one the
     * customer is already signed into — so the account is made first here.
     */
    private String redeemOntoASignedInCustomer(String code) throws Exception {
        String email = "topup-customer-" + java.util.UUID.randomUUID() + "@example.com";
        User customer = userRepository.save(User.builder()
                .name("Walk-in Customer").email(email)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .emailVerified(true)
                .build());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readValue(login.getResponse().getContentAsString(),
                com.gridstore.huevista.auth.dto.AuthResponse.class).getAccessToken();

        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        return customer.getId();
    }

    private static GrantCodeProjectsRequest grant(int projects) {
        GrantCodeProjectsRequest req = new GrantCodeProjectsRequest();
        req.setProjects(projects);
        return req;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void seedSubscription(String email, SubscriptionStatus status) {
        User owner = userRepository.findByEmail(email).orElseThrow();
        subscriptionRepository.save(Subscription.builder()
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(status)
                .projectsLimit(60)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build());
    }

    private void expireSubscription(String email) {
        User owner = userRepository.findByEmail(email).orElseThrow();
        subscriptionRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()).forEach(sub -> {
            sub.setStatus(SubscriptionStatus.EXPIRED);
            sub.setCurrentPeriodEnd(LocalDateTime.now().minusDays(1));
            subscriptionRepository.saveAndFlush(sub);
        });
    }

    private String createOrg(String token, String name, String slug) throws Exception {
        CreateOrgRequest req = new CreateOrgRequest();
        req.setName(name);
        req.setSlug(slug);
        req.setType(OrgType.RETAILER);
        MvcResult result = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String registerAndLogin(String email, String name, UserRole role) throws Exception {
        userRepository.save(User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .role(role)
                .build());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
