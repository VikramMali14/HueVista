package com.gridstore.huevista.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.CustomerEntitlement;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.account.repository.ProjectGrantRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.account.dto.CreateOrgRequest;
import com.gridstore.huevista.account.model.OrgType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giving projects away costs the shop, and can be undone — up to a point.
 *
 * Granting used to be a bare {@code allowance + 1} that reserved nothing, so a shop could
 * hand out unlimited projects while its subscription showed no change, even though
 * issuing a CODE for the same projects charged properly. These pin the corrected shape:
 * the reservation happens, the grant is recorded, and it comes back only while it is
 * genuinely unused AND the billing period that funded it is still running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ProjectGrantIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired CustomerEntitlementRepository entitlementRepository;
    @Autowired ProjectGrantRepository grantRepository;
    @Autowired com.gridstore.huevista.account.repository.OrganizationRepository organizationRepository;
    @Autowired com.gridstore.huevista.account.repository.CustomerAccessCodeRepository codeRepository;

    private static final String SHOP_EMAIL = "grant-shop@example.com";
    private static final String CUSTOMER_EMAIL = "grant-customer@example.com";

    private String shopToken;
    private String orgId;
    private String customerId;
    private String subscriptionId;

    @BeforeEach
    void setUp() throws Exception {
        shopToken = registerAndLogin(SHOP_EMAIL, "Grant Shop", UserRole.RETAILER);
        orgId = createOrg("Grant Paints", "grant-paints");
        subscriptionId = seedSubscription();

        User customer = userRepository.save(User.builder()
                .name("Walk-in").email(CUSTOMER_EMAIL)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());
        customerId = customer.getId();

        entitlementRepository.saveAndFlush(CustomerEntitlement.builder()
                .customer(customer)
                .retailerOrg(organizationRepository.findById(orgId).orElseThrow())
                .accessExpiresAt(LocalDateTime.now().plusDays(10))
                .projectAllowance(1)
                .projectsCreated(0)
                .build());
    }

    @Test
    void grantingReservesImagesAgainstTheShopsPlan() throws Exception {
        int before = subscriptionRepository.findById(subscriptionId).orElseThrow().getReservedProjects();

        grant(2).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectAllowance").value(3));

        // The whole point: the shop's quota moved. It never used to.
        assertThat(subscriptionRepository.findById(subscriptionId).orElseThrow().getReservedProjects())
                .isEqualTo(before + 2);
    }

    @Test
    void aLapsedShopCannotGiveProjectsAway() throws Exception {
        expireSubscription();
        grant(1).andExpect(status().isPaymentRequired());
    }

    @Test
    void anUnusedGrantComesBackAndReturnsTheImages() throws Exception {
        grant(2).andExpect(status().isOk());
        String grantId = firstGrantId();
        int reserved = subscriptionRepository.findById(subscriptionId).orElseThrow().getReservedProjects();

        mockMvc.perform(delete("/api/organizations/{orgId}/project-grants/{id}", orgId, grantId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        assertThat(entitlementRepository.findByCustomerId(customerId).orElseThrow()
                .getProjectAllowance()).isEqualTo(1);
        assertThat(subscriptionRepository.findById(subscriptionId).orElseThrow().getReservedProjects())
                .isEqualTo(reserved - 2);
    }

    /** A project the customer has actually made is spent — the image was paid for. */
    @Test
    void aUsedGrantCannotBeTakenBack() throws Exception {
        grant(2).andExpect(status().isOk());
        String grantId = firstGrantId();

        // Allowance 3, and the customer has now made all three.
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customerId).orElseThrow();
        ent.setProjectsCreated(3);
        entitlementRepository.saveAndFlush(ent);

        mockMvc.perform(delete("/api/organizations/{orgId}/project-grants/{id}", orgId, grantId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().is4xxClientError());
    }

    /**
     * The renewal boundary. Images reserved in one period cannot be released into the
     * next — that would hand the new period a credit the old one paid for, minting quota
     * once per renewal for as long as anyone kept granting and revoking.
     */
    @Test
    void aGrantFromAnEarlierBillingPeriodCannotBeTakenBack() throws Exception {
        grant(1).andExpect(status().isOk());
        String grantId = firstGrantId();

        // The plan renews: same subscription, new period.
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElseThrow();
        sub.setCurrentPeriodStart(LocalDateTime.now().plusSeconds(1));
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(30));
        subscriptionRepository.saveAndFlush(sub);

        mockMvc.perform(delete("/api/organizations/{orgId}/project-grants/{id}", orgId, grantId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().is4xxClientError());

        // And the listing agrees, so the button is never offered in the first place.
        mockMvc.perform(get("/api/organizations/{orgId}/project-grants", orgId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revocable").value(false));
    }

    @Test
    void takingTheSameGrantBackTwiceIsRefused() throws Exception {
        grant(1).andExpect(status().isOk());
        String grantId = firstGrantId();

        mockMvc.perform(delete("/api/organizations/{orgId}/project-grants/{id}", orgId, grantId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/organizations/{orgId}/project-grants/{id}", orgId, grantId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void anUnusedGrantIsListedAsRevocable() throws Exception {
        grant(1).andExpect(status().isOk());
        mockMvc.perform(get("/api/organizations/{orgId}/project-grants", orgId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projects").value(1))
                .andExpect(jsonPath("$[0].revocable").value(true));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A customer who redeems a SECOND shop's code must not vanish from the first.
     *
     * {@code CustomerEntitlement.retailerOrg} is one pointer and redeeming moves it. So a
     * shop that onboarded a customer and paid for their projects lost them entirely the
     * moment they redeemed somewhere else: gone from the portal list, and refused by
     * grant-project with "not managed by your organization" — while that shop's
     * paid-for allowance was still on the row. Both shops have a real relationship with
     * the customer, and the code each issued is the evidence of it.
     */
    @Test
    void aShopKeepsItsCustomerAfterTheyRedeemSomewhereElse() throws Exception {
        // The customer holds a code this shop issued…
        codeRepository.saveAndFlush(CustomerAccessCode.builder()
                .organization(organizationRepository.findById(orgId).orElseThrow())
                .code("SHOPAAA1")
                .validDays(10)
                .expiresAt(LocalDateTime.now().plusDays(10))
                .customerName("Walk-in")
                .usedByUser(userRepository.findById(customerId).orElseThrow())
                .usedAt(LocalDateTime.now())
                .build());

        // …then redeems a different shop's, which moves "managed by" away.
        String otherToken = registerAndLogin("other-shop@example.com", "Other Shop", UserRole.RETAILER);
        String otherOrgId = createOrgAs(otherToken, "Other Paints", "other-paints");
        var entitlement = entitlementRepository.findByCustomerId(customerId).orElseThrow();
        entitlement.setRetailerOrg(organizationRepository.findById(otherOrgId).orElseThrow());
        entitlementRepository.saveAndFlush(entitlement);

        // The first shop still sees them…
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/organizations/{orgId}/customers", orgId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].customerId").value(customerId));

        // …and can still give them a project.
        grant(1).andExpect(status().isOk());
    }

    /** A shop with no relationship to the customer at all is still refused. */
    @Test
    void anUnrelatedShopStillCannotGrantToSomeoneElsesCustomer() throws Exception {
        String otherToken = registerAndLogin("stranger-shop@example.com", "Stranger", UserRole.RETAILER);
        String otherOrgId = createOrgAs(otherToken, "Stranger Paints", "stranger-paints");

        mockMvc.perform(post("/api/organizations/{orgId}/customers/{customerId}/grant-project",
                        otherOrgId, customerId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projects\":1}"))
                .andExpect(status().isForbidden());
    }

    private String createOrgAs(String token, String name, String slug) throws Exception {
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

    private org.springframework.test.web.servlet.ResultActions grant(int projects) throws Exception {
        return mockMvc.perform(post("/api/organizations/{orgId}/customers/{customerId}/grant-project",
                        orgId, customerId)
                .header("Authorization", "Bearer " + shopToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projects\":" + projects + "}"));
    }

    private String firstGrantId() {
        return grantRepository.findByRetailerOrgIdOrderByCreatedAtDesc(orgId).get(0).getId();
    }

    private String seedSubscription() {
        User owner = userRepository.findByEmail(SHOP_EMAIL).orElseThrow();
        Subscription sub = subscriptionRepository.saveAndFlush(Subscription.builder()
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .projectsLimit(60)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build());
        return sub.getId();
    }

    private void expireSubscription() {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElseThrow();
        sub.setStatus(SubscriptionStatus.EXPIRED);
        sub.setCurrentPeriodEnd(LocalDateTime.now().minusDays(1));
        subscriptionRepository.saveAndFlush(sub);
    }

    private String createOrg(String name, String slug) throws Exception {
        CreateOrgRequest req = new CreateOrgRequest();
        req.setName(name);
        req.setSlug(slug);
        req.setType(OrgType.RETAILER);
        MvcResult result = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private String registerAndLogin(String email, String name, UserRole role) throws Exception {
        userRepository.save(User.builder()
                .name(name).email(email)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(role).build());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }

    /**
     * A shop-onboarded customer who runs out is NOT offered a purchase.
     *
     * Their projects were assigned by a shop and paid for out of that shop's quota. The
     * refusal carries its own code so the studio asks the shop rather than opening
     * Checkout — selling them one direct would take money for something the shop already
     * owns, and quietly move the relationship off the counter.
     */
    @Test
    void aCustomerOutOfProjectsIsSentToTheirShopNotToCheckout() throws Exception {
        CustomerEntitlement ent = entitlementRepository.findByCustomerId(customerId).orElseThrow();
        ent.setProjectsCreated(ent.getProjectAllowance());
        entitlementRepository.saveAndFlush(ent);

        String customerToken = login(CUSTOMER_EMAIL);
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"whatever\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("ASK_RETAILER"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Ask ")));
    }

    /** And they can send that ask from the app — the shop grants in one click. */
    @Test
    void theCustomerCanAskTheirShopForAnother() throws Exception {
        String customerToken = login(CUSTOMER_EMAIL);
        mockMvc.perform(post("/api/me/request-more-projects")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isAccepted());
    }

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                r.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
