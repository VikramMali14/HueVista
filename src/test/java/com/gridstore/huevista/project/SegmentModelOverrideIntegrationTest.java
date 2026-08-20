package com.gridstore.huevista.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.SegmentationService;
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
 * Pinning one run to a named image model — the admin testing panel's "clean this photo
 * with FLUX 2 Max instead" — from the request in to the row it lands on.
 *
 * <p>Both halves of that sentence are load-bearing and neither is covered anywhere else.
 * The model id is pasted into a Replicate URL, so a caller who is not an admin must not
 * be able to set one at all, and an admin must not be able to set one that is not on the
 * list. And because the worker reads the choice from the PROJECT ROW rather than from
 * the request — it may be a different JVM entirely — a knob that validated correctly but
 * never persisted would silently run the configured model and look like it worked.
 *
 * <p>The segmentation worker itself is mocked out: what is under test is the request
 * handling, and a real async run would go looking for a photo that only exists as a
 * storage key in an H2 row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class SegmentModelOverrideIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    /** The run is not the subject here — the request that asks for it is. */
    @MockitoBean SegmentationService segmentationService;
    /** No Redis in tests; the queue would otherwise refuse the enqueue with a 500. */
    @MockitoBean com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriptionRepository subscriptionRepository;

    private String adminToken;
    private String retailerToken;
    private String adminRoomId;
    private String retailerRoomId;

    @BeforeEach
    void setUp() throws Exception {
        User admin = subscribed(userRepository.save(User.builder()
                .name("Platform Admin")
                .email("model-admin@huevista.test")
                .password(passwordEncoder.encode("admin-pass"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN)
                .emailVerified(true)
                .build()));
        adminToken = login("model-admin@huevista.test", "admin-pass");
        adminRoomId = room(admin, "Admin's test room");

        User retailer = subscribed(userRepository.save(User.builder()
                .name("Asha Paints")
                .email("model-shop@huevista.test")
                .password(passwordEncoder.encode("shop-pass"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.RETAILER)
                .emailVerified(true)
                .build()));
        retailerToken = login("model-shop@huevista.test", "shop-pass");
        retailerRoomId = room(retailer, "Front elevation");
    }

    private User subscribed(User user) {
        subscriptionRepository.save(Subscription.builder()
                .user(user)
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
        return user;
    }

    private String room(User owner, String name) {
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(owner)
                .originalFilename("room.jpg")
                .storageKey("test/room-" + java.util.UUID.randomUUID() + ".jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.OUTDOOR)
                .build());
        return projectRepository.save(Project.builder()
                .user(owner)
                .image(image)
                .name(name)
                .status(ProjectStatus.CREATED)
                .build()).getId();
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                AuthResponse.class).getAccessToken();
    }

    /** What the (mocked-out) worker would have done: leave the room segmented. */
    private void finishTheRun() {
        Project project = projectRepository.findById(adminRoomId).orElseThrow();
        project.setStatus(ProjectStatus.SEGMENTED);
        projectRepository.save(project);
    }

    private org.springframework.test.web.servlet.ResultActions segment(
            String roomId, String token, String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + roomId + "/segment")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ─── The catalogue ───────────────────────────────────────────────────────

    @Test
    void theModelListIsServedToAdminsOnly() throws Exception {
        mockMvc.perform(get("/api/projects/ai-models"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/projects/ai-models").header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/projects/ai-models").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("google/nano-banana-pro"))
                .andExpect(jsonPath("$[0].label").exists())
                .andExpect(jsonPath("$[0].family").value("NANO_BANANA"));
    }

    @Test
    void theListIsNotSwallowedByTheProjectDetailRoute() throws Exception {
        // "ai-models" sits under /api/projects/{id}. A literal segment outranks a path
        // variable in Spring's routing table, but only as long as nobody turns it into
        // one — so this asserts it resolves to the list rather than to "no such project".
        mockMvc.perform(get("/api/projects/ai-models").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ─── Setting a model ─────────────────────────────────────────────────────

    @Test
    void anAdminsChoiceLandsOnTheProjectForTheWorkerToRead() throws Exception {
        segment(adminRoomId, adminToken, """
                {"maskMode":"AUTO",
                 "cleanModel":"black-forest-labs/flux-2-max",
                 "maskModel":"google/nano-banana-2"}""")
                .andExpect(status().isOk());

        Project project = projectRepository.findById(adminRoomId).orElseThrow();
        assertThat(project.getCleanModel()).isEqualTo("black-forest-labs/flux-2-max");
        assertThat(project.getMaskModel()).isEqualTo("google/nano-banana-2");
    }

    @Test
    void anEmptyChoiceIsHowAComparisonIsLeftBehind() throws Exception {
        segment(adminRoomId, adminToken, "{\"cleanModel\":\"bytedance/seedream-4\"}")
                .andExpect(status().isOk());
        assertThat(projectRepository.findById(adminRoomId).orElseThrow().getCleanModel())
                .isEqualTo("bytedance/seedream-4");

        // That run finishes before the next one is asked for; without this the second
        // request is a 409 "already in progress", since the worker is mocked out here.
        finishTheRun();

        // The studio sends "" on every ordinary run. If that were ignored rather than
        // cleared, one comparison would keep serving every later run of this room with
        // nothing on screen saying which model was answering.
        segment(adminRoomId, adminToken, "{\"cleanModel\":\"\"}")
                .andExpect(status().isOk());
        assertThat(projectRepository.findById(adminRoomId).orElseThrow().getCleanModel()).isNull();
    }

    @Test
    void aModelOutsideTheCatalogueIsRefusedRatherThanForwarded() throws Exception {
        segment(adminRoomId, adminToken, "{\"cleanModel\":\"attacker/anything\"}")
                .andExpect(status().isBadRequest());

        // And nothing was written on the way to the refusal.
        assertThat(projectRepository.findById(adminRoomId).orElseThrow().getCleanModel()).isNull();
    }

    // ─── Everyone else ───────────────────────────────────────────────────────

    @Test
    void aNonAdminCannotPinAModelAtAll() throws Exception {
        // Not a 403 — the endpoint is every shop's, and maskMode is their real choice.
        // The admin knobs are stripped from the request instead, so a crafted body runs
        // an ordinary pipeline rather than being refused.
        segment(retailerRoomId, retailerToken, """
                {"maskMode":"MANUAL",
                 "cleanModel":"black-forest-labs/flux-2-max",
                 "maskModel":"bytedance/seedream-4"}""")
                .andExpect(status().isOk());

        Project project = projectRepository.findById(retailerRoomId).orElseThrow();
        assertThat(project.getCleanModel()).isNull();
        assertThat(project.getMaskModel()).isNull();
        assertThat(project.getMaskMode()).isEqualTo("MANUAL");
    }

    @Test
    void aNonAdminSendingAModelThatDoesNotExistIsNotEvenValidated() throws Exception {
        // The field never reaches the catalogue for them, so a junk value is not a 400 —
        // it is simply not there. Worth pinning: the opposite would let any shop probe
        // which model ids this deployment knows about.
        segment(retailerRoomId, retailerToken, "{\"cleanModel\":\"attacker/anything\"}")
                .andExpect(status().isOk());

        assertThat(projectRepository.findById(retailerRoomId).orElseThrow().getCleanModel()).isNull();
    }

    // ─── The clean-up choices ────────────────────────────────────────────────
    //
    // Same endpoint, and pinned separately because these change what the CUSTOMER's
    // canvas looks like rather than which supplier made it. Three of them are now every
    // signed-in caller's to make — the studio asks before it sends the photo — and the
    // fourth, houseType, is not: it overrides what the photo plainly is, which serves a
    // comparison and nothing else.

    @Test
    void anAdminsPromptChoicesLandOnTheProjectForTheWorkerToRead() throws Exception {
        segment(adminRoomId, adminToken, """
                {"maskMode":"AUTO",
                 "analysePhoto":true,
                 "houseType":"BATHROOM",
                 "cleanFurnishing":"EMPTY",
                 "cleanAngle":"BEST_VIEW"}""")
                .andExpect(status().isOk());

        Project project = projectRepository.findById(adminRoomId).orElseThrow();
        assertThat(project.getAnalysePhoto()).isTrue();
        assertThat(project.getHouseType()).isEqualTo("BATHROOM");
        assertThat(project.getCleanFurnishing()).isEqualTo("EMPTY");
        assertThat(project.getCleanAngle()).isEqualTo("BEST_VIEW");
    }

    @Test
    void aNonAdminsCleanUpChoicesLandOnTheProjectToo() throws Exception {
        // Shipped admin-first, opened up once the clean-up was worth running for real.
        // These three describe a picture the person at the screen is about to look at,
        // so a retailer's answers reach the worker exactly as an admin's do.
        segment(retailerRoomId, retailerToken, """
                {"maskMode":"AUTO",
                 "analysePhoto":true,
                 "cleanFurnishing":"EMPTY",
                 "cleanAngle":"BEST_VIEW"}""")
                .andExpect(status().isOk());

        Project project = projectRepository.findById(retailerRoomId).orElseThrow();
        assertThat(project.getAnalysePhoto()).isTrue();
        assertThat(project.getCleanFurnishing()).isEqualTo("EMPTY");
        assertThat(project.getCleanAngle()).isEqualTo("BEST_VIEW");
        assertThat(project.getMaskMode()).isEqualTo("AUTO");
    }

    @Test
    void aNonAdminStillCannotOverrideWhatThePhotoIs() throws Exception {
        // houseType is the one that stayed behind: it does not describe a photo, it
        // contradicts one, and the only thing that buys is a prompt comparison. Not a
        // 403 — the rest of the body is theirs — so it is simply not there.
        segment(retailerRoomId, retailerToken, """
                {"maskMode":"AUTO","houseType":"BATHROOM"}""")
                .andExpect(status().isOk());

        assertThat(projectRepository.findById(retailerRoomId).orElseThrow().getHouseType())
                .isNull();
    }

    @Test
    void aNonAdminsTypoOnACleanUpChoiceIsRefusedRatherThanIgnored() throws Exception {
        // Their field now, so their typo gets the same answer an admin's does. Silently
        // running AS_SHOT for someone who asked for a re-framed view would hand back a
        // canvas that looks like the request was honoured.
        segment(retailerRoomId, retailerToken, "{\"cleanAngle\":\"DRONE\"}")
                .andExpect(status().isBadRequest());

        assertThat(projectRepository.findById(retailerRoomId).orElseThrow().getCleanAngle())
                .isNull();
    }

    @Test
    void aHouseTypeOutsideTheEnumIsRefusedRatherThanQuietlyIgnored() throws Exception {
        // Refused, not defaulted: an admin comparing two house-type clauses cannot tell
        // from the image which clause actually ran, so a typo that silently ran the
        // stock prompt would answer nothing. Same reasoning as the model catalogue.
        segment(adminRoomId, adminToken, "{\"houseType\":\"CONSERVATORY\"}")
                .andExpect(status().isBadRequest());
        assertThat(projectRepository.findById(adminRoomId).orElseThrow().getHouseType()).isNull();
    }

    @Test
    void aTypoOnFurnishingOrAngleIsRefusedToo() throws Exception {
        segment(adminRoomId, adminToken, "{\"cleanFurnishing\":\"STAGED\"}")
                .andExpect(status().isBadRequest());
        segment(adminRoomId, adminToken, "{\"cleanAngle\":\"DRONE\"}")
                .andExpect(status().isBadRequest());

        Project project = projectRepository.findById(adminRoomId).orElseThrow();
        assertThat(project.getCleanFurnishing()).isNull();
        assertThat(project.getCleanAngle()).isNull();
    }

    @Test
    void blankHandsTheHouseTypeBackToTheAnalysis() throws Exception {
        segment(adminRoomId, adminToken, "{\"houseType\":\"SHOPFRONT\"}")
                .andExpect(status().isOk());
        assertThat(projectRepository.findById(adminRoomId).orElseThrow().getHouseType())
                .isEqualTo("SHOPFRONT");

        finishTheRun();

        // The studio sends "" on every run where the admin did not override, for the
        // same reason it sends "" for the models: a type pinned once must not keep
        // shaping every later run of this room with nothing on screen saying so.
        segment(adminRoomId, adminToken, "{\"houseType\":\"\"}")
                .andExpect(status().isOk());
        assertThat(projectRepository.findById(adminRoomId).orElseThrow().getHouseType()).isNull();
    }
}
