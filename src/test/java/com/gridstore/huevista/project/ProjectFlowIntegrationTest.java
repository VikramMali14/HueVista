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
        // verified, with an active free-trial subscription (one project included).
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
                .aiGenerationsUsed(0)
                .aiGenerationsLimit(Plan.PROFESSIONAL.getMonthlyImageLimit())
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
