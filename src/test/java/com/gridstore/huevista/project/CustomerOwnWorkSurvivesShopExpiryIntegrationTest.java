package com.gridstore.huevista.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.dto.CreateOrgRequest;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.CustomerEntitlementRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
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
 * A customer can hold projects from two quite separate places, and this pins the line
 * between them.
 *
 * <ul>
 *   <li>A SHOP gave them projects on an access code. The shop paid, out of its monthly
 *       image quota, for a fixed window — so when that window closes those rooms close
 *       with it. That is the deal and it stays.</li>
 *   <li>They BOUGHT projects themselves. Nobody's plan is involved and no shop has any
 *       claim on them; each carries its own validity window.</li>
 * </ul>
 *
 * The two used to be collapsed into one: merely holding a shop entitlement routed every
 * creation through it and locked every read behind it. So a customer who bought a room
 * and later redeemed a code from a paint shop found that, ten days later, the shop's
 * expiry had taken away work they had paid for before ever walking into that shop — and
 * an unspent credit they could not spend, because the create path refused before it ever
 * asked about it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class CustomerOwnWorkSurvivesShopExpiryIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired CustomerEntitlementRepository entitlementRepository;
    @Autowired ProjectCreditService projectCreditService;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String SHOP_EMAIL = "expiry-shop@example.com";
    private static final String CUSTOMER_EMAIL = "bought-my-own@example.com";

    private String shopToken;
    private String orgId;
    private String customerToken;
    private String customerId;

    @BeforeEach
    void setUp() throws Exception {
        shopToken = registerAndLogin(SHOP_EMAIL, "Expiry Paints Owner", UserRole.RETAILER);
        orgId = createOrg(shopToken, "Expiry Paints", "expiry-paints");
        seedActiveSubscription(SHOP_EMAIL);

        // Signed up on their own — no shop behind them yet, which is the point: the shop
        // arrives later, after they have already paid for work of their own.
        User customer = userRepository.save(User.builder()
                .name("Anjali Nair")
                .email(CUSTOMER_EMAIL)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .emailVerified(true)
                .build());
        customerId = customer.getId();
        customerToken = login(CUSTOMER_EMAIL);
    }

    /**
     * The whole story in one pass: buy a room, redeem a shop's code, make a room on the
     * shop's tab, let the shop's window close.
     *
     * The shop's room shuts. The bought room does not, and the list still shows both —
     * the dashboard used to answer 403 for the entire page.
     */
    @Test
    void aShopsExpiryClosesTheShopsRoomAndLeavesTheBoughtOneOpen() throws Exception {
        projectCreditService.creditPurchasedProject(customerId, ProjectCredit.Source.PURCHASE);
        String boughtRoom = createProject("Room I paid for");

        redeemOntoThisAccount(generateCode(1).get("code").asText());
        String shopRoom = createProject("Room the shop gave me");

        // Linked to the code (the counter reads its shades) vs. the customer's own.
        assertThat(projectRepository.findAccessCodeIdById(shopRoom)).isPresent();
        assertThat(projectRepository.findAccessCodeIdById(boughtRoom)).isEmpty();

        closeTheShopsWindow();

        mockMvc.perform(get("/api/projects/{id}", shopRoom)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/projects/{id}", boughtRoom)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Room I paid for"));

        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * A credit the customer bought is spendable whatever the shop's code is doing. The
     * shop route is tried first and simply reports that it cannot pay, rather than
     * refusing on everyone's behalf.
     */
    @Test
    void aBoughtCreditStillCreatesARoomAfterTheShopsWindowCloses() throws Exception {
        redeemOntoThisAccount(generateCode(1).get("code").asText());
        createProject("Room the shop gave me");
        closeTheShopsWindow();

        // Nothing bought yet: this genuinely is the shop's conversation to have.
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + newImage() + "\",\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());

        projectCreditService.creditPurchasedProject(customerId, ProjectCredit.Source.PURCHASE);
        String mine = createProject("Bought after the code lapsed");

        // Their money, their room: no shop code stamped on it (which would have spent one
        // of the shop's held image credits and filed the room behind the closed window),
        // it opens, and it carries the AI image a paid-for room includes.
        assertThat(projectRepository.findAccessCodeIdById(mine)).isEmpty();
        mockMvc.perform(get("/api/projects/{id}", mine)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rendersAllowed").value(1));
    }

    /**
     * The refusal a shop-onboarded customer with nothing bought should still hear, in both
     * of its forms: a closed window is 403 "your access has ended", a used-up allowance is
     * the 402 that puts "grant one more" in front of the counter. Loosening the gate must
     * not have quietly turned either into the generic "buy a project" pitch aimed at
     * customers who have no shop at all.
     */
    @Test
    void aShopCustomerWithNothingBoughtStillHearsFromTheirShop() throws Exception {
        redeemOntoThisAccount(generateCode(1).get("code").asText());
        createProject("The one project on my code");

        // Allowance used up, window still open → ask the shop for another.
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + newImage() + "\",\"name\":\"One more\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("ASK_RETAILER"));

        closeTheShopsWindow();

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + newImage() + "\",\"name\":\"One more\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("access has ended")));
    }

    /**
     * Redeeming while signed in adds the code to the account in hand — it does not open a
     * second one — so the rooms already made stay owned by the same account, and a shop's
     * allowance is added to whatever the customer already had.
     */
    @Test
    void redeemingWhileSignedInKeepsTheAccountAndItsRooms() throws Exception {
        projectCreditService.creditPurchasedProject(customerId, ProjectCredit.Source.PURCHASE);
        String boughtRoom = createProject("Room I paid for");

        redeemOntoThisAccount(generateCode(2).get("code").asText());

        assertThat(userRepository.findByEmail(CUSTOMER_EMAIL).orElseThrow().getId())
                .isEqualTo(customerId);
        assertThat(projectRepository.findUserIdById(boughtRoom)).contains(customerId);
        assertThat(entitlementRepository.findByCustomerId(customerId).orElseThrow()
                .getProjectAllowance()).isEqualTo(2);
    }

    /** A shop account typing a customer's code must never be demoted to CUSTOMER. */
    @Test
    void aShopAccountCannotRedeemACustomersCode() throws Exception {
        String code = generateCode(1).get("code").asText();

        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isConflict());

        assertThat(userRepository.findByEmail(SHOP_EMAIL).orElseThrow().getRole())
                .isEqualTo(UserRole.RETAILER);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Push the customer's access window into the past, as ten quiet days would. */
    private void closeTheShopsWindow() {
        var ent = entitlementRepository.findByCustomerId(customerId).orElseThrow();
        ent.setAccessExpiresAt(LocalDateTime.now().minusMinutes(1));
        entitlementRepository.saveAndFlush(ent);
    }

    private String createProject(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + newImage() + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String newImage() {
        return imageRepository.save(UploadedImage.builder()
                .user(userRepository.findById(customerId).orElseThrow())
                .originalFilename("room.jpg")
                .storageKey("test/room-" + java.util.UUID.randomUUID() + ".jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build()).getId();
    }

    /** The signed-in redemption: the code joins this account rather than making a new one. */
    private void redeemOntoThisAccount(String code) throws Exception {
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode generateCode(int projectQuota) throws Exception {
        GenerateAccessCodeRequest req = new GenerateAccessCodeRequest();
        req.setCustomerName("Anjali Nair");
        req.setProjectQuota(projectQuota);
        MvcResult result = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", orgId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
        return login(email);
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
