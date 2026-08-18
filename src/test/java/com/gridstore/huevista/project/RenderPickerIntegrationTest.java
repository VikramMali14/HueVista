package com.gridstore.huevista.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.AiCreditService;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectPdfPage;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.repository.ProjectPdfPageRepository;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectRenderWorker;
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
 * Starting an AI image from the images page rather than from inside a room.
 *
 * <p>Two things are being pinned. WHICH rooms are offered — closed, and carrying at least
 * one colour-board combination — because both halves of that rule exist to stop the picker
 * offering a choice that dead-ends on the next screen. And WHICH photograph the model is
 * given, which used to be a decision the code made silently and is now the customer's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class RenderPickerIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    /** Mocked so nothing reaches an image model — only the accepted request is asserted. */
    @MockitoBean ProjectRenderWorker worker;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectPdfPageRepository pageRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired AiCreditService aiCreditService;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private User owner;

    /** Closed, two combinations, and a cleaned photograph — the room the picker is for. */
    private String finishedId;

    @BeforeEach
    void setUp() throws Exception {
        owner = userRepository.save(User.builder()
                .name("Picker Owner")
                .email("picker@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneNumber("+919886547344")
                .phoneVerified(true)
                .build());

        subscriptionRepository.save(Subscription.builder()
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .projectsUsed(0)
                .projectsLimit(Plan.PROFESSIONAL.getMonthlyProjectLimit())
                .pdfDownloadsUsed(0)
                .pdfDownloadsLimit(Plan.PROFESSIONAL.getMonthlyPdfLimit())
                .pdfImageLimit(Plan.PROFESSIONAL.getPdfImageLimit())
                .build());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"picker@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readValue(login.getResponse().getContentAsString(),
                AuthResponse.class).getAccessToken();

        // Every image is bought with a credit now, so the wallet is part of the fixture
        // rather than something only the payment tests care about.
        aiCreditService.grant(owner.getId(), 5, "admin", "render picker test");

        finishedId = room("Finished hall", true, 2, true);
    }

    /**
     * @param closed      whether the job is finished
     * @param combos      how many colour-board pages it handed over
     * @param cleanedPhoto whether the clean-up produced a second photograph
     */
    private String room(String name, boolean closed, int combos, boolean cleanedPhoto) {
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(owner)
                .originalFilename("room.jpg")
                .storageKey("test/original-" + java.util.UUID.randomUUID() + ".jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());

        Project project = projectRepository.save(Project.builder()
                .user(owner)
                .image(image)
                .name(name)
                .status(ProjectStatus.SEGMENTED)
                .cleanedImageStorageKey(cleanedPhoto
                        ? "test/cleaned-" + java.util.UUID.randomUUID() + ".jpg" : null)
                .closedAt(closed ? LocalDateTime.now() : null)
                .build());

        for (int i = 0; i < combos; i++) {
            pageRepository.save(ProjectPdfPage.builder()
                    .project(project)
                    .boardIndex(1)
                    .pageIndex(i)
                    .title("Option " + (i + 1))
                    .build());
        }
        return project.getId();
    }

    private String firstComboOf(String projectId) throws Exception {
        return objectMapper.readTree(
                        mockMvc.perform(get("/api/projects/{id}/combos", projectId)
                                        .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .get(0).get("id").asText();
    }

    /** @param sourceImage the JSON fragment for the field, or "" to leave it out entirely. */
    private MvcResult requestRender(String projectId, String comboId, String sourceImage)
            throws Exception {
        return mockMvc.perform(post("/api/projects/{id}/renders", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"comboId":"%s","timeOfDay":"DAY","borderMode":"KEEP_ORIGINAL",
                                  "lighting":"NATURAL","furnishing":"KEEP","style":"MODERN"%s}"""
                                .formatted(comboId, sourceImage)))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    // ─── Which rooms are offered ─────────────────────────────────────────────

    @Test
    void aFinishedRoomWithCombinationsIsOffered() throws Exception {
        mockMvc.perform(get("/api/me/renderable-projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(finishedId))
                .andExpect(jsonPath("$[0].name").value("Finished hall"))
                .andExpect(jsonPath("$[0].comboCount").value(2))
                .andExpect(jsonPath("$[0].closedAt").isNotEmpty())
                // Both photographs travel, because choosing between them is the next step.
                .andExpect(jsonPath("$[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$[0].cleanedImageUrl").isNotEmpty());
    }

    /** Still being worked on — it is reached from the studio it is open in, not from here. */
    @Test
    void anOpenRoomIsNotOffered() throws Exception {
        room("Still going", false, 2, true);

        mockMvc.perform(get("/api/me/renderable-projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(finishedId));
    }

    /**
     * Closed without ever taking a board, so there is no combination to photograph.
     * Offering it would put a room in the picker that dead-ends on the next screen.
     */
    @Test
    void aFinishedRoomThatNeverTookABoardIsNotOffered() throws Exception {
        room("Closed empty-handed", true, 0, true);

        mockMvc.perform(get("/api/me/renderable-projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(finishedId));
    }

    /**
     * A room whose clean-up never produced a second photograph says so with a null, which
     * is what tells the picker there is no choice to offer rather than one with a single
     * real option in it.
     */
    @Test
    void aRoomWithNoCleanedPhotographSaysSoWithANull() throws Exception {
        String uncleanedId = room("Uncleaned", true, 1, false);

        String body = mockMvc.perform(get("/api/me/renderable-projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode uncleaned = null;
        for (com.fasterxml.jackson.databind.JsonNode row : objectMapper.readTree(body)) {
            if (uncleanedId.equals(row.get("id").asText())) {
                uncleaned = row;
            }
        }
        org.assertj.core.api.Assertions.assertThat(uncleaned).isNotNull();
        org.assertj.core.api.Assertions
                .assertThat(uncleaned.get("imageUrl").asText()).isNotBlank();
        org.assertj.core.api.Assertions
                .assertThat(uncleaned.get("cleanedImageUrl").isNull()).isTrue();
    }

    /** Somebody else's finished room is not this account's to photograph. */
    @Test
    void anotherAccountsRoomIsNotOffered() throws Exception {
        User stranger = userRepository.save(User.builder()
                .name("Stranger")
                .email("stranger-picker@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .build());
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(stranger)
                .originalFilename("theirs.jpg")
                .storageKey("test/theirs.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());
        Project theirs = projectRepository.save(Project.builder()
                .user(stranger).image(image).name("Their hall")
                .status(ProjectStatus.SEGMENTED).closedAt(LocalDateTime.now()).build());
        pageRepository.save(ProjectPdfPage.builder()
                .project(theirs).boardIndex(1).pageIndex(0).title("Theirs").build());

        mockMvc.perform(get("/api/me/renderable-projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(finishedId));
    }

    // ─── Which photograph the model is given ─────────────────────────────────

    @Test
    void theOriginalPhotographCanBeAskedForAndIsRecorded() throws Exception {
        MvcResult res = requestRender(finishedId, firstComboOf(finishedId),
                ",\"sourceImage\":\"ORIGINAL\"");

        org.assertj.core.api.Assertions
                .assertThat(objectMapper.readTree(res.getResponse().getContentAsString())
                        .get("sourceImage").asText())
                .isEqualTo("ORIGINAL");
    }

    /**
     * Saying nothing gets the cleaned photograph — what every image made before this
     * choice existed was given, and the better starting point in the ordinary case.
     */
    @Test
    void sayingNothingGetsTheCleanedPhotograph() throws Exception {
        MvcResult res = requestRender(finishedId, firstComboOf(finishedId), "");

        org.assertj.core.api.Assertions
                .assertThat(objectMapper.readTree(res.getResponse().getContentAsString())
                        .get("sourceImage").asText())
                .isEqualTo("CLEANED");
    }

    /** An image is bought with a credit whatever photograph it is made from. */
    @Test
    void theImageIsPaidForWithACreditEitherWay() throws Exception {
        int before = aiCreditService.balance(owner.getId());

        requestRender(finishedId, firstComboOf(finishedId), ",\"sourceImage\":\"ORIGINAL\"");

        org.assertj.core.api.Assertions.assertThat(aiCreditService.balance(owner.getId()))
                .isLessThan(before);
    }
}
