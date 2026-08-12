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
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Click-to-segment must run against the picture the user clicked on.
 *
 * <p>The studio displays the CLEANED canvas; SAM was being handed the ORIGINAL photo,
 * and the click — which arrives normalised — was being scaled by the original's pixel
 * size. Two separate errors in one call: SAM traced a photo that still contained the
 * wires and the parked car the clean had removed, and the point it was told to trace
 * from was measured against a differently sized image.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ClickToSegmentCanvasIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    /** Mocked so the click never reaches Replicate; what it was ASKED is the test. */
    @MockitoBean SegmentationService segmentationService;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final int ORIGINAL_W = 1000;
    private static final int ORIGINAL_H = 500;
    /** The clean generates at its own size and is then upscaled locally, so the canvas
     *  the user clicks is nothing like the photo's pixel size. */
    private static final int CLEANED_W = 3840;
    private static final int CLEANED_H = 1920;

    private String token;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        User user = userRepository.save(User.builder()
                .name("Click User")
                .email("clickuser@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneNumber("+919886547399")
                .phoneVerified(true)
                .build());
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

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"clickuser@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readValue(login.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user)
                .originalFilename("room.jpg")
                .storageKey("test/original.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .width(ORIGINAL_W)
                .height(ORIGINAL_H)
                .imageType(ImageType.INDOOR)
                .build());

        Project project = projectRepository.save(Project.builder()
                .user(user)
                .image(image)
                .name("Click room")
                .build());
        projectId = project.getId();

        when(segmentationService.segmentPointAndSave(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyDouble(), any()))
                .thenReturn(Region.builder()
                        .project(project).label("Wall").category(RegionCategory.MANUAL)
                        .maskUrl("masks/manual.png").maskData("masks/manual.png")
                        .build());
    }

    private void click() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/segment/point")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":0.5,\"y\":0.25,\"label\":\"Wall\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void segmentsTheCleanedCanvasAtItsOwnSize() throws Exception {
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.setCleanedImageStorageKey("test/cleaned.jpg");
        project.setCleanedImageWidth(CLEANED_W);
        project.setCleanedImageHeight(CLEANED_H);
        projectRepository.save(project);

        click();

        // The cleaned image, and the click measured against ITS dimensions — not the
        // original's, which would put the point at a quarter of the way across a
        // picture the user never saw.
        verify(segmentationService).segmentPointAndSave(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.contains("cleaned"),
                org.mockito.ArgumentMatchers.eq(CLEANED_W),
                org.mockito.ArgumentMatchers.eq(CLEANED_H),
                org.mockito.ArgumentMatchers.eq(0.5),
                org.mockito.ArgumentMatchers.eq(0.25),
                org.mockito.ArgumentMatchers.eq("Wall"));
    }

    @Test
    void fallsBackToTheOriginalPhotoWhenThereIsNoCleanedCanvas() throws Exception {
        // Cleaner off, clean failed, or a project cleaned before the canvas size was
        // recorded: the original photo is what the studio is showing, so it is also
        // what SAM should trace.
        click();

        verify(segmentationService).segmentPointAndSave(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.contains("original"),
                org.mockito.ArgumentMatchers.eq(ORIGINAL_W),
                org.mockito.ArgumentMatchers.eq(ORIGINAL_H),
                anyDouble(), anyDouble(), any());
    }

    @Test
    void aCleanedKeyWithNoRecordedSizeStillUsesTheOriginal() throws Exception {
        // Projects cleaned before the size columns existed. Sending the cleaned image
        // with the original's dimensions would be the worst of both worlds, so the
        // pair is used only when both halves are present.
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.setCleanedImageStorageKey("test/cleaned.jpg");
        projectRepository.save(project);

        click();

        verify(segmentationService).segmentPointAndSave(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.contains("original"),
                org.mockito.ArgumentMatchers.eq(ORIGINAL_W),
                org.mockito.ArgumentMatchers.eq(ORIGINAL_H),
                anyDouble(), anyDouble(), any());
    }
}
