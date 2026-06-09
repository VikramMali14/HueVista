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
import com.gridstore.huevista.project.dto.CreateProjectRequest;
import com.gridstore.huevista.project.repository.ProjectRepository;
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
    @Autowired ProjectRepository projectRepository;
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
                .aiGenerationsLimit(Plan.PROFESSIONAL.getMonthlyAiLimit())
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
}
