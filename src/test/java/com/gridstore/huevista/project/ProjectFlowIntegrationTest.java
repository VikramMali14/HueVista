package com.gridstore.huevista.project;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.CreateProjectRequest;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import com.razorpay.RazorpayClient;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ProjectFlowIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired StorageService storageService;
    @Autowired ProjectRepository projectRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String userToken;
    private String imageId;
    private String userId;

    @BeforeEach
    void setUp() throws Exception {
        // A retailer who can create projects under the new rules: email + mobile
        // verified, on an active trial of a paid tier with its full monthly allowance.
        User user = userRepository.save(User.builder()
                .name("Project User")
                .email("projectuser@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneNumber("+919886547321")
                .phoneVerified(true)
                .build());
        userId = user.getId();

        subscriptionRepository.save(Subscription.builder()
                .user(user)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .trial(true)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(14))
                .projectsUsed(0)
                .projectsLimit(Plan.PROFESSIONAL.getMonthlyProjectLimit())
                .build());

        // Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"projectuser@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse authResp = objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        userToken = authResp.getAccessToken();

        // Create a test image directly in the repository (skip the upload/Claude flow)
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user)
                .originalFilename("test-room.jpg")
                .storageKey("test/room.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());
        imageId = image.getId();
    }

    @Test
    void create_project_list_and_delete() throws Exception {
        // Create
        CreateProjectRequest req = new CreateProjectRequest();
        req.setImageId(imageId);
        req.setName("Living Room Makeover");

        MvcResult createResult = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Living Room Makeover"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();

        String projectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // List
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(projectId));

        // Get by ID
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));

        // Rename (PATCH semantics — only the provided field changes)
        mockMvc.perform(patch("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bedroom Refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bedroom Refresh"));

        // A blank name is rejected — the project must stay findable by name.
        mockMvc.perform(patch("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // Delete
        mockMvc.perform(delete("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());

        // Verify deleted
        assertThat(projectRepository.findById(projectId)).isEmpty();
    }

    @Test
    void cannot_access_another_users_project() throws Exception {
        // Create a project for the first user
        CreateProjectRequest req = new CreateProjectRequest();
        req.setImageId(imageId);

        MvcResult createResult = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String projectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // Create a second user
        userRepository.save(User.builder()
                .name("Other User")
                .email("other@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .build());

        MvcResult otherLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"other@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String otherToken = objectMapper.readValue(otherLogin.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();

        // Second user tries to access first user's project
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void share_link_is_publicly_accessible() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest();
        req.setImageId(imageId);

        MvcResult createResult = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String projectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // Generate share link
        MvcResult shareResult = mockMvc.perform(post("/api/projects/" + projectId + "/share")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validDays\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").isNotEmpty())
                .andReturn();

        String shareToken = objectMapper.readTree(shareResult.getResponse().getContentAsString())
                .get("shareToken").asText();

        // Access shared project without auth
        mockMvc.perform(get("/api/share/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));
    }

    /**
     * Sharing twice must not kill the link already sent.
     *
     * A share URL is forwarded — to a spouse, a builder, a WhatsApp group. Minting a
     * fresh token on every call meant pressing Share again (to change the companies, or
     * simply because the dialog was reopened) silently invalidated the URL that was
     * already out there, and the recipient saw nothing but "this link has expired".
     */
    @Test
    void sharing_again_keeps_the_link_that_was_already_sent() throws Exception {
        String projectId = createProject();

        String first = shareToken(projectId, "{\"validDays\": 7}");
        String second = shareToken(projectId, "{\"validDays\": 10}");

        assertThat(second).isEqualTo(first);
        mockMvc.perform(get("/api/share/" + first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));
    }

    /** …and withdrawing one is a deliberate act, not a side effect of re-sharing. */
    @Test
    void a_share_link_can_be_withdrawn_on_purpose() throws Exception {
        String projectId = createProject();
        String token = shareToken(projectId, "{\"validDays\": 7}");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/projects/" + projectId + "/share")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/share/" + token))
                .andExpect(status().isNotFound());

        // Sharing again mints a genuinely new link.
        assertThat(shareToken(projectId, "{\"validDays\": 7}")).isNotEqualTo(token);
    }

    /**
     * The link that gets forwarded must open the ROOM, not the JSON behind it.
     *
     * It used to be minted against the API origin — `…:8080/api/share/{token}` — so
     * anyone who opened it without the app got the raw response body: a wall of JSON
     * where a painted room should have been. The website serves `/share/{token}` for
     * exactly this, and that is what a share link has to point at.
     */
    @Test
    void a_share_link_points_at_the_website_page_not_the_api() throws Exception {
        String projectId = createProject();

        MvcResult res = mockMvc.perform(post("/api/projects/" + projectId + "/share")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validDays\": 7}"))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(res.getResponse().getContentAsString());
        String shareUrl = body.get("shareUrl").asText();

        // http://localhost:3000 is the CORS allowed origin in application-test.properties,
        // which is where the website lives when app.web-base-url is not set explicitly.
        assertThat(shareUrl).isEqualTo("http://localhost:3000/share/" + body.get("shareToken").asText());
        assertThat(shareUrl).doesNotContain("/api/");
    }

    private String shareToken(String projectId, String body) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/projects/" + projectId + "/share")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("shareToken").asText();
    }

    @Test
    void shared_project_image_is_publicly_streamable() throws Exception {
        // Store a real file so the public, token-scoped share-image endpoint can read it.
        byte[] bytes = "fake-png-bytes".getBytes();
        String key = storageService.store(bytes, userId, "room.png", "image/png");
        UploadedImage stored = imageRepository.save(UploadedImage.builder()
                .user(userRepository.findById(userId).orElseThrow())
                .originalFilename("room.png")
                .storageKey(key)
                .contentType("image/png")
                .fileSize(bytes.length)
                .imageType(ImageType.INDOOR)
                .build());

        CreateProjectRequest req = new CreateProjectRequest();
        req.setImageId(stored.getId());
        MvcResult createResult = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String projectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        MvcResult shareResult = mockMvc.perform(post("/api/projects/" + projectId + "/share")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validDays\": 7}"))
                .andExpect(status().isOk())
                .andReturn();
        String shareToken = objectMapper.readTree(shareResult.getResponse().getContentAsString())
                .get("shareToken").asText();

        // In local-storage mode the public projection rewrites the image URL to the
        // token-scoped endpoint (the owner-authenticated /api/images path would 401 here).
        mockMvc.perform(get("/api/share/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("/api/share/" + shareToken + "/image"));

        // And that endpoint streams the bytes to an ANONYMOUS viewer (no auth header).
        mockMvc.perform(get("/api/share/" + shareToken + "/image"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));
    }

    // ── Refining an AI mask after segmentation ──

    @Test
    void user_can_replace_an_ai_detected_regions_mask() throws Exception {
        String projectId = createProject();

        // Seed an AI-detected region (manual = false) with an initial stored mask,
        // as segmentation would leave it.
        byte[] originalMask = onePixelPng(0xFFFFFFFF);
        String originalKey = storageService.store(originalMask, userId, "main_wall.png", "image/png");
        Region region = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Main wall")
                .category(RegionCategory.MAIN_WALL)
                .maskUrl(originalKey)
                .maskData(originalKey)
                .displayOrder(0)
                .manual(false)
                .build());

        // Refine it: the user sends a corrected mask for the SAME region.
        byte[] refinedMask = onePixelPng(0xFF000000);
        String body = objectMapper.writeValueAsString(Map.of(
                "maskBase64", "data:image/png;base64," + Base64.getEncoder().encodeToString(refinedMask)));

        mockMvc.perform(put("/api/projects/{id}/regions/{rid}/mask", projectId, region.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(region.getId()))
                // Category and label are untouched — only the mask changed.
                .andExpect(jsonPath("$.category").value("MAIN_WALL"))
                .andExpect(jsonPath("$.label").value("Main wall"));

        // The region now points at a NEW mask carrying the refined bytes, and it is
        // still an AI region (not flipped to hand-drawn), so segmentation semantics hold.
        Region updated = regionRepository.findById(region.getId()).orElseThrow();
        assertThat(updated.getMaskUrl()).isNotEqualTo(originalKey);
        assertThat(updated.isManual()).isFalse();
        assertThat(storageService.load(updated.getMaskUrl())).isEqualTo(refinedMask);
    }

    @Test
    void replacing_the_mask_of_a_missing_region_is_404() throws Exception {
        String projectId = createProject();
        String body = objectMapper.writeValueAsString(Map.of(
                "maskBase64", "data:image/png;base64," + Base64.getEncoder().encodeToString(onePixelPng(0xFFFFFFFF))));

        mockMvc.perform(put("/api/projects/{id}/regions/{rid}/mask", projectId, 999_999)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void replacing_a_region_mask_with_a_non_png_is_rejected() throws Exception {
        String projectId = createProject();
        Region region = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Trim")
                .category(RegionCategory.TRIM)
                .displayOrder(0)
                .manual(false)
                .build());

        String body = objectMapper.writeValueAsString(Map.of(
                "maskBase64", Base64.getEncoder().encodeToString("not a png".getBytes())));

        mockMvc.perform(put("/api/projects/{id}/regions/{rid}/mask", projectId, region.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * A wall found by DETECTION can be removed, not only a hand-drawn one.
     *
     * This used to be refused with a 400. Detection routinely produces surfaces
     * nobody wants painted — an accent wall the customer is keeping, a ceiling, a
     * strip of floor read as wall — and while they could not be removed they stayed
     * in the wall strip, the palette and every page of the colour board for the life
     * of the room. The only escape was deleting the project and paying for another.
     */
    @Test
    void a_detected_wall_can_be_deleted() throws Exception {
        String projectId = createProject();
        String maskKey = storageService.store(onePixelPng(0xFFFFFFFF), userId, "accent.png", "image/png");
        Region detected = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Accent wall")
                .category(RegionCategory.ACCENT_WALL)
                .maskUrl(maskKey)
                .maskData(maskKey)
                .displayOrder(0)
                .manual(false)
                .build());

        mockMvc.perform(delete("/api/projects/{id}/regions/{rid}", projectId, detected.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());

        assertThat(regionRepository.findById(detected.getId())).isEmpty();
    }

    /** And a hand-drawn one still can, which is what it could always do. */
    @Test
    void a_hand_drawn_wall_can_still_be_deleted() throws Exception {
        String projectId = createProject();
        Region drawn = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("My wall")
                .category(RegionCategory.MANUAL)
                .displayOrder(0)
                .manual(true)
                .build());

        mockMvc.perform(delete("/api/projects/{id}/regions/{rid}", projectId, drawn.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());

        assertThat(regionRepository.findById(drawn.getId())).isEmpty();
    }

    /**
     * The LAST wall goes too. A room with no walls paints nothing, but it is not a
     * dead end — drawing one by hand is free and unlimited — and refusing here would
     * block the one deletion someone with a single badly-detected wall most needs.
     */
    @Test
    void the_last_wall_can_be_deleted() throws Exception {
        String projectId = createProject();
        Region only = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Wall")
                .category(RegionCategory.MAIN_WALL)
                .displayOrder(0)
                .manual(false)
                .build());

        mockMvc.perform(delete("/api/projects/{id}/regions/{rid}", projectId, only.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());

        assertThat(regionRepository.countByProjectId(projectId)).isZero();
    }

    @Test
    void deleting_a_wall_of_someone_elses_project_is_404() throws Exception {
        String projectId = createProject();
        Region region = regionRepository.save(Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Wall")
                .category(RegionCategory.MAIN_WALL)
                .displayOrder(0)
                .manual(false)
                .build());

        // A region id that is real, under a project id that is not this caller's.
        mockMvc.perform(delete("/api/projects/{id}/regions/{rid}", "not-my-project", region.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());

        assertThat(regionRepository.findById(region.getId())).isPresent();
    }

    // ── helpers ──

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest();
        req.setImageId(imageId);
        req.setName("Room");
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** A valid 1×1 PNG of the given ARGB colour — written by ImageIO so it always
     *  decodes back (a hand-crafted base64 blob risks silently failing the decode). */
    private static byte[] onePixelPng(int argb) throws Exception {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
