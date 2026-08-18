package com.gridstore.huevista.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.dto.CreateOrgRequest;
import com.gridstore.huevista.account.dto.GenerateAccessCodeRequest;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.service.AiCreditService;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.model.ProjectRender;
import com.gridstore.huevista.project.repository.ProjectRenderRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer's own AI images, from the account's side: close the room, buy the picture,
 * then go looking for it later.
 *
 * <p>{@code GET /api/me/renders} is the only thing standing between a customer and an
 * image they paid for. The per-project list needs a project id, which is exactly what
 * somebody who wants their picture back a week later does not have — so this endpoint IS
 * the "where is my AI image?" answer, and it had no test of any kind. Every property the
 * shelf leans on was unpinned: that a CUSTOMER may call it at all, that closing the room
 * does not take the image away with it, and that one account cannot see another's.
 *
 * <p>Written against a CUSTOMER rather than the retailer the other project suites use.
 * The endpoint carries {@code @RequiresFeature(STUDIO)} — a distributor→shop grant — and
 * the whole question is whether an account that cannot possibly hold such a grant is let
 * through. A retailer passing proves nothing about that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// One board closes a project here, so a test that wants a CLOSED room can take a single
// download to get one. Pinned rather than inherited: the product's cap is four and may
// move again, and this suite is about what a closed room does with its AI image.
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = "app.project.colour-boards-per-project=1")
class MyAiImagesIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRenderRepository renderRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired AiCreditService aiCreditService;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String SHOP_EMAIL = "ai-images-shop@example.com";
    private static final String CUSTOMER_EMAIL = "ai-images-customer@example.com";

    private String shopToken;
    private String orgId;
    private String customerToken;
    private String customerId;
    private String projectId;
    private String codeId;

    @BeforeEach
    void setUp() throws Exception {
        shopToken = registerAndLogin(SHOP_EMAIL, "Shop Owner", UserRole.RETAILER);
        orgId = createOrg("Mehta Paints", "mehta-paints");
        seedActiveSubscription(SHOP_EMAIL);

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

        // A shop onboards them, which is what pays for the colour board at the end —
        // a customer rides on their shop's plan for that.
        JsonNode code = generateCode();
        codeId = code.get("id").asText();
        redeemOntoThisAccount(code.get("code").asText());
        projectId = createProject("Front bedroom");

        // The picture itself is never included — a shop-granted room carries no render
        // allowance, and the AI image is bought from the customer's own wallet. Topped up
        // here so the flow under test is the ordinary one and not a payment failure.
        aiCreditService.grant(customerId, 5, "test", "seeded for the AI image flow");
    }

    // ─── The flow the shelf exists for ───────────────────────────────────────

    /**
     * The whole journey in one pass: take the colour board (which closes the room), buy
     * the AI image, and find it again from the ACCOUNT rather than from the project.
     */
    @Test
    void theImageBoughtAfterClosingTheRoomIsOnTheAccountsShelf() throws Exception {
        String comboId = closeWithABoard();
        String renderId = requestRender(comboId);
        finish(renderId, "renders/front-bedroom.png");

        mockMvc.perform(get("/api/me/renders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(renderId))
                .andExpect(jsonPath("$[0].status").value("READY"))
                // The room it came from, so the shelf can name the picture without opening
                // the project behind it — the entire point of the page.
                .andExpect(jsonPath("$[0].projectId").value(projectId))
                .andExpect(jsonPath("$[0].projectName").value("Front bedroom"))
                .andExpect(jsonPath("$[0].imageUrl").isNotEmpty())
                // And the combination's shades, so one image can be printed on its own
                // sheet. A picture of a room nobody can buy paint from is half a
                // deliverable.
                .andExpect(jsonPath("$[0].shades.length()").value(2));
    }

    /**
     * Closing is not deleting.
     *
     * The image is made FROM a closed room — closing is what unlocks buying one — so a
     * shelf that dropped closed rooms' pictures would be empty for precisely the images
     * that exist. Asserted explicitly because the query behind it joins the project, and
     * a filter added there for any other reason would take these with it.
     */
    @Test
    void aClosedRoomKeepsItsImageOnTheShelf() throws Exception {
        finish(requestRender(closeWithABoard()), "renders/closed-room.png");

        mockMvc.perform(get("/api/projects/{id}", projectId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        mockMvc.perform(get("/api/me/renders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * A picture still being painted is not a picture yet.
     *
     * QUEUED and RUNNING belong to the studio that asked for them and is polling it;
     * FAILED ones have already handed their credit back. Either on the shelf would be a
     * card with no image behind it.
     */
    @Test
    void anUnfinishedImageIsNotOnTheShelfYet() throws Exception {
        requestRender(closeWithABoard());

        mockMvc.perform(get("/api/me/renders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * The scoping, stated once. The query is keyed by the owner, so this is a property of
     * the read rather than a check in front of it — worth pinning, because that is exactly
     * the sort of guarantee a later "just add a filter here" can quietly undo.
     */
    @Test
    void oneAccountsImagesAreNotAnothersShelf() throws Exception {
        finish(requestRender(closeWithABoard()), "renders/mine.png");

        userRepository.save(User.builder()
                .name("Someone Else")
                .email("someone-else@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .emailVerified(true)
                .build());

        mockMvc.perform(get("/api/me/renders")
                        .header("Authorization", "Bearer " + login("someone-else@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ─── The shop's side of the same shelf ───────────────────────────────────
    //
    // A shop pays for the room, prints the colour board and takes the order, and until now
    // could not see the one thing the customer walks out with. These pin who may read it.

    /** The counter can open the picture made in a room its own code paid for. */
    @Test
    void theShopSeesTheImageMadeUnderItsOwnCode() throws Exception {
        String renderId = requestRender(closeWithABoard());
        finish(renderId, "renders/front-bedroom.png");

        mockMvc.perform(get("/api/access-codes/{codeId}/renders", codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(renderId))
                .andExpect(jsonPath("$[0].projectName").value("Front bedroom"))
                .andExpect(jsonPath("$[0].imageUrl").isNotEmpty())
                // The shades, so the counter can read the order off the picture — which is
                // the only reason a shop wants to see one of these at all.
                .andExpect(jsonPath("$[0].shades.length()").value(2));
    }

    /** Unfinished ones are left out here for the same reason as on the customer's shelf. */
    @Test
    void theShopSeesNothingUntilTheImageIsFinished() throws Exception {
        requestRender(closeWithABoard());

        mockMvc.perform(get("/api/access-codes/{codeId}/renders", codeId)
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * And no further. A code is one shop's, and the endpoint takes that code's id straight
     * off the URL — so the authorisation is the whole boundary here, unlike the customer's
     * own shelf where the query itself cannot reach another account.
     */
    @Test
    void anotherShopCannotReadThisCodesImages() throws Exception {
        finish(requestRender(closeWithABoard()), "renders/not-yours.png");

        String rivalToken = registerAndLogin("rival-shop@example.com", "Rival Owner", UserRole.RETAILER);

        mockMvc.perform(get("/api/access-codes/{codeId}/renders", codeId)
                        .header("Authorization", "Bearer " + rivalToken))
                .andExpect(status().isForbidden());
    }

    /** Nor can the customer read it by this route — it is the shop's view, and their own
     *  shelf is the one that answers for them. */
    @Test
    void theCustomerCannotReadTheShopsViewOfTheirImages() throws Exception {
        finish(requestRender(closeWithABoard()), "renders/mine.png");

        mockMvc.perform(get("/api/access-codes/{codeId}/renders", codeId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Takes the room's one colour board — which closes it — and returns the combo id the
     *  AI image is made from. */
    private String closeWithABoard() throws Exception {
        mockMvc.perform(post("/api/projects/{id}/colour-boards", projectId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"pages":[{"title":"Calm","shades":[
                                   {"regionLabel":"Main wall","shadeCode":"AP-1","shadeName":"Calm","hex":"#e8d5b0"},
                                   {"regionLabel":"Trim","shadeCode":"AP-T1","shadeName":"Dark Clove","hex":"#4a362a"}
                                 ]}]}"""))
                .andExpect(status().isOk());

        return objectMapper.readTree(
                        mockMvc.perform(get("/api/projects/{id}/combos", projectId)
                                        .header("Authorization", "Bearer " + customerToken))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .get(0).get("id").asText();
    }

    private String requestRender(String comboId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/projects/{id}/renders", projectId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"comboId":"%s","timeOfDay":"DAY","borderMode":"KEEP_ORIGINAL",
                                  "lighting":"NATURAL","furnishing":"KEEP","style":"MODERN"}"""
                                .formatted(comboId)))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    /** What the worker's {@code succeed} does, without a worker: the image landed. */
    private void finish(String renderId, String storageKey) {
        ProjectRender render = renderRepository.findById(renderId).orElseThrow();
        render.setStatus(ProjectRender.Status.READY);
        render.setStorageKey(storageKey);
        render.setCompletedAt(LocalDateTime.now());
        renderRepository.saveAndFlush(render);
    }

    private String createProject(String name) throws Exception {
        String imageId = imageRepository.save(UploadedImage.builder()
                .user(userRepository.findById(customerId).orElseThrow())
                .originalFilename("room.jpg")
                .storageKey("test/room-" + java.util.UUID.randomUUID() + ".jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build()).getId();

        MvcResult res = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + imageId + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    /** The signed-in redemption: the code joins this account rather than making a new one. */
    private void redeemOntoThisAccount(String code) throws Exception {
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode generateCode() throws Exception {
        GenerateAccessCodeRequest req = new GenerateAccessCodeRequest();
        req.setCustomerName("Anjali Nair");
        req.setProjectQuota(3);
        MvcResult res = mockMvc.perform(post("/api/organizations/{orgId}/access-codes", orgId)
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
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
                .pdfDownloadsUsed(0)
                .pdfDownloadsLimit(Plan.PROFESSIONAL.getMonthlyPdfLimit())
                .pdfImageLimit(Plan.PROFESSIONAL.getPdfImageLimit())
                .build());
    }

    private String createOrg(String name, String slug) throws Exception {
        CreateOrgRequest req = new CreateOrgRequest();
        req.setName(name);
        req.setSlug(slug);
        req.setType(OrgType.RETAILER);
        MvcResult res = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
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
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(
                res.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
