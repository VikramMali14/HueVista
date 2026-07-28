package com.gridstore.huevista.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.dto.CreateOrgRequest;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.dto.GrantCodeProjectsRequest;
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
    @Autowired CustomerEntitlementRepository entitlementRepository;
    @Autowired com.gridstore.huevista.account.service.AccessCodeService accessCodeService;

    private static final String SHOP_EMAIL = "topup-shop@example.com";

    private String shopToken;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        shopToken = registerAndLogin(SHOP_EMAIL, "Top-up Shop", UserRole.RETAILER);
        orgId = createOrg(shopToken, "Top-up Paints", "topup-paints");
        seedSubscription(SHOP_EMAIL, SubscriptionStatus.ACTIVE);
    }

    @Test
    void grantingMoreProjectsRaisesTheQuotaAndHoldsMoreImages() throws Exception {
        JsonNode code = generateCode(2);
        String codeId = code.get("id").asText();
        assertThat(code.get("projectQuota").asInt()).isEqualTo(2);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectQuota").value(5))
                .andExpect(jsonPath("$.projectsRemaining").value(5));

        // Every added project holds one more image credit, exactly as generation does —
        // otherwise the shop hands out work it never reserved quota for.
        assertThat(codeRepository.findById(codeId).orElseThrow().getReservedImages()).isEqualTo(5);
    }

    /**
     * The customer's own allowance is what actually gates their rooms. Moving only the
     * code's quota would leave them holding a code that promises five projects and an
     * entitlement that refuses the third.
     */
    @Test
    void grantingMoreProjectsAlsoRaisesTheRedeemingCustomersAllowance() throws Exception {
        JsonNode code = generateCode(1);
        String customerId = redeemIntoNewAccount(code.get("code").asText());
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

    /**
     * Extension resets the window to a fresh 10 days rather than adding to what is left,
     * so however often a code is renewed it never carries more than the 10 days the
     * customer was promised when they were handed it.
     */
    @Test
    void extendingResetsTheWindowToTenDaysFromNow() throws Exception {
        JsonNode code = generateCode(1);
        String codeId = code.get("id").asText();

        // Age the code so the reset is visible: 2 days left, not 10.
        var stored = codeRepository.findById(codeId).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().plusDays(2));
        codeRepository.saveAndFlush(stored);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensionCount").value(1))
                .andExpect(jsonPath("$.expired").value(false));

        LocalDateTime expiry = codeRepository.findById(codeId).orElseThrow().getExpiresAt();
        assertThat(expiry).isAfter(LocalDateTime.now().plusDays(9))
                .isBefore(LocalDateTime.now().plusDays(11));
    }

    /** An already-expired code can be brought back — that is the day-11 walk-in. */
    @Test
    void anExpiredCodeCanBeExtendedBackToLife() throws Exception {
        JsonNode code = generateCode(1);
        String codeId = code.get("id").asText();
        var stored = codeRepository.findById(codeId).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().minusDays(1));
        codeRepository.saveAndFlush(stored);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(false));
    }

    /** Extending moves the customer's access window with it, not just the code's. */
    @Test
    void extendingAlsoMovesTheRedeemingCustomersAccessWindow() throws Exception {
        JsonNode code = generateCode(1);
        String customerId = redeemIntoNewAccount(code.get("code").asText());

        var ent = entitlementRepository.findByCustomerId(customerId).orElseThrow();
        ent.setAccessExpiresAt(LocalDateTime.now().plusDays(1));
        entitlementRepository.saveAndFlush(ent);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend",
                        orgId, code.get("id").asText())
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk());

        assertThat(entitlementRepository.findByCustomerId(customerId).orElseThrow()
                .getAccessExpiresAt()).isAfter(LocalDateTime.now().plusDays(9));
    }

    @Test
    void aLapsedShopCanNeitherGrantProjectsNorExtend() throws Exception {
        JsonNode code = generateCode(1);
        String codeId = code.get("id").asText();
        expireSubscription(SHOP_EMAIL);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/projects", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(grant(1))))
                .andExpect(status().isPaymentRequired());

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
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

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().is4xxClientError());
    }

    /**
     * An account created by redeeming a code has no real e-mail — only one synthesised
     * from the code — so nothing user-facing should present it as a contact address.
     */
    @Test
    void aRedeemedAccountReportsNoEmailAnywhereUserFacing() throws Exception {
        JsonNode code = generateCode(1);
        MvcResult redeemed = mockMvc.perform(post("/api/access-codes/redeem-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.get("code").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").doesNotExist())
                .andReturn();
        String customerToken = objectMapper.readTree(redeemed.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());

        // …and the shop's own customer list shows the name they typed, not the placeholder.
        mockMvc.perform(get("/api/organizations/{orgId}/customers", orgId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerEmail").doesNotExist());
    }

    /**
     * The expiry sweep must reclaim holds from a code that WAS redeemed.
     *
     * This was the larger half of a permanent quota leak. A shop issuing a code for five
     * projects reserves five image credits; a customer who creates two leaves three held.
     * Revoking is refused once a code is redeemed, the sweep only looked at UNREDEEMED
     * codes, and {@code reservedImages} deliberately survives a renewal — so those three
     * credits were subtracted from the shop's effective allowance in every future billing
     * period, forever. A shop issuing codes at any steady rate eventually had none left.
     */
    @Test
    void expirySweepReturnsHoldsFromARedeemedCodeTheCustomerDidNotFullyUse() throws Exception {
        JsonNode code = generateCode(5);
        String codeId = code.get("id").asText();
        String shopUserId = userRepository.findByEmail(SHOP_EMAIL).orElseThrow().getId();

        // Issuing held five credits against the shop's plan.
        assertThat(heldImagesFor(shopUserId)).isEqualTo(5);

        // The customer redeems it — from here revoking is refused, by design.
        redeemIntoNewAccount(code.get("code").asText());
        assertThat(codeRepository.findById(codeId).orElseThrow().isUsed()).isTrue();

        // …and then their ten days run out with projects still unused.
        var expired = codeRepository.findById(codeId).orElseThrow();
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        codeRepository.saveAndFlush(expired);

        accessCodeService.releaseExpiredCodeQuota();

        // The unspent holds are back on the shop's quota, and the code is stamped so a
        // second sweep can never refund it twice.
        assertThat(heldImagesFor(shopUserId)).isZero();
        var swept = codeRepository.findById(codeId).orElseThrow();
        assertThat(swept.getReservedImages()).isZero();
        assertThat(swept.getQuotaReleasedAt()).isNotNull();

        // Idempotent: running it again changes nothing.
        accessCodeService.releaseExpiredCodeQuota();
        assertThat(heldImagesFor(shopUserId)).isZero();
    }

    /** A code that is still live keeps its holds — the customer may yet use them. */
    @Test
    void expirySweepLeavesALiveRedeemedCodeAlone() throws Exception {
        JsonNode code = generateCode(3);
        String shopUserId = userRepository.findByEmail(SHOP_EMAIL).orElseThrow().getId();
        redeemIntoNewAccount(code.get("code").asText());

        accessCodeService.releaseExpiredCodeQuota();

        assertThat(heldImagesFor(shopUserId)).isEqualTo(3);
        assertThat(codeRepository.findById(code.get("id").asText()).orElseThrow()
                .getQuotaReleasedAt()).isNull();
    }

    /**
     * "Extend" must never shorten a code that was sold with a longer window.
     *
     * The extension was hardcoded to ten days AND overwrote the code's own validDays, so
     * a kiosk code sold with (say) thirty days was cut to ten the moment a shop pressed
     * Extend — taking away access the walk-in had already paid for, under a button
     * labelled as giving them more.
     */
    @Test
    void extendingALongerCodeNeverShortensIt() throws Exception {
        JsonNode issued = generateCode(1);
        String codeId = issued.get("id").asText();

        // Stand this code up as a kiosk-style 30-day one.
        var code = codeRepository.findById(codeId).orElseThrow();
        code.setValidDays(30);
        code.setExpiresAt(LocalDateTime.now().plusDays(30));
        codeRepository.saveAndFlush(code);
        LocalDateTime before = code.getExpiresAt();

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk());

        var extended = codeRepository.findById(codeId).orElseThrow();
        assertThat(extended.getExpiresAt()).isAfterOrEqualTo(before);
        assertThat(extended.getValidDays()).isEqualTo(30);
    }

    /** A code near the end of its window is pushed out by its OWN validity, not a flat ten. */
    @Test
    void extendingUsesTheCodesOwnWindow() throws Exception {
        JsonNode issued = generateCode(1);
        String codeId = issued.get("id").asText();

        var code = codeRepository.findById(codeId).orElseThrow();
        code.setValidDays(30);
        code.setExpiresAt(LocalDateTime.now().plusHours(1)); // almost out of time
        codeRepository.saveAndFlush(code);

        mockMvc.perform(post("/api/organizations/{orgId}/access-codes/{codeId}/extend", orgId, codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk());

        assertThat(codeRepository.findById(codeId).orElseThrow().getExpiresAt())
                .isAfter(LocalDateTime.now().plusDays(29));
    }

    private int heldImagesFor(String userId) {
        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().map(Subscription::getReservedImages).orElse(0);
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
    private String redeemIntoNewAccount(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/access-codes/redeem-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("user").get("id").asText();
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
                .aiGenerationsLimit(60)
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
