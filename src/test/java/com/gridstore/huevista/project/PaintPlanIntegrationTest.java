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
import com.gridstore.huevista.project.repository.RegionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The paint plan: which of a room's surfaces are being painted, and what each one is.
 *
 * <p>A project's regions are everything anybody found or drew — detection returns what it
 * sees, the Mask Studio adds what the customer outlines — and both answer "what is
 * paintable here" rather than "what am I painting". Somebody who marks out ten surfaces to
 * get the shapes right and wants three of them coloured needs to be able to say so, and
 * the only way to say it used to be deletion: irreversible for a detected wall without
 * spending another credit, and wrong for a decision people change every time they try a
 * different scheme.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class PaintPlanIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private String projectId;
    private Long wallId;
    private Long ceilingId;

    @BeforeEach
    void setUp() throws Exception {
        User user = userRepository.save(User.builder()
                .name("Plan User")
                .email("planuser@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneNumber("+919886547311")
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
                        .content("{\"email\":\"planuser@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readValue(login.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user).originalFilename("room.jpg").storageKey("test/original.jpg")
                .contentType("image/jpeg").fileSize(1024L).width(1000).height(500)
                .imageType(ImageType.INDOOR)
                .build());
        Project project = projectRepository.save(Project.builder()
                .user(user).image(image).name("Plan room").build());
        projectId = project.getId();

        wallId = regionRepository.save(Region.builder()
                .project(project).label("Back wall").category(RegionCategory.OTHER_WALL)
                .maskUrl("masks/back.png").displayOrder(0)
                .appliedShadeCode("AP-1").appliedHexCode("#d98c8c")
                .build()).getId();
        ceilingId = regionRepository.save(Region.builder()
                .project(project).label("Ceiling").category(RegionCategory.OTHER_WALL)
                .maskUrl("masks/ceiling.png").displayOrder(1)
                .build()).getId();
    }

    private void savePlan(String body) throws Exception {
        mockMvc.perform(put("/api/projects/" + projectId + "/regions/plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    /** Every wall a project has ever had is in the scheme until somebody says otherwise. */
    @Test
    void wallsAreInThePlanUntilTheyAreTakenOut() {
        assertThat(regionRepository.findById(wallId).orElseThrow().isInPlan()).isTrue();
        assertThat(regionRepository.findById(ceilingId).orElseThrow().isInPlan()).isTrue();
    }

    @Test
    void takesAWallOutOfTheSchemeAndPutsItBack() throws Exception {
        savePlan("[{\"regionId\":" + ceilingId + ",\"inPlan\":false}]");
        assertThat(regionRepository.findById(ceilingId).orElseThrow().isInPlan()).isFalse();

        savePlan("[{\"regionId\":" + ceilingId + ",\"inPlan\":true}]");
        assertThat(regionRepository.findById(ceilingId).orElseThrow().isInPlan()).isTrue();
    }

    /**
     * Excluding is not deleting, and it is not a repaint either.
     *
     * The commonest reason to take a surface out is to look at a scheme without it, so
     * ticking it back has to bring the room back exactly as it was. A wall that lost its
     * colour on the way out would make every exclusion a decision the customer cannot
     * undo — the precise problem the flag exists to fix.
     */
    @Test
    void leavesTheWallAndItsColourexactlyWhereTheyWere() throws Exception {
        savePlan("[{\"regionId\":" + wallId + ",\"inPlan\":false}]");

        Region wall = regionRepository.findById(wallId).orElseThrow();
        assertThat(wall.isInPlan()).isFalse();
        assertThat(wall.getMaskUrl()).isEqualTo("masks/back.png");
        assertThat(wall.getAppliedShadeCode()).isEqualTo("AP-1");
        assertThat(wall.getAppliedHexCode()).isEqualTo("#d98c8c");
    }

    /** The role decides which colour of a combination lands on a wall, so it is the
     *  customer's to set — "no, THAT one is the accent". */
    @Test
    void changesWhatAWallIsInTheScheme() throws Exception {
        savePlan("[{\"regionId\":" + wallId + ",\"category\":\"ACCENT_WALL\",\"label\":\"Chimney breast\"}]");

        Region wall = regionRepository.findById(wallId).orElseThrow();
        assertThat(wall.getCategory()).isEqualTo(RegionCategory.ACCENT_WALL);
        assertThat(wall.getLabel()).isEqualTo("Chimney breast");
        // Untouched by a write that never mentioned it.
        assertThat(wall.isInPlan()).isTrue();
    }

    /** PATCH per field: a write that re-labels one wall must not reset the two fields it
     *  never mentioned. */
    @Test
    void leavesEveryFieldTheWriteDidNotName() throws Exception {
        savePlan("[{\"regionId\":" + wallId + ",\"category\":\"TRIM\",\"inPlan\":false}]");
        savePlan("[{\"regionId\":" + wallId + ",\"label\":\"Skirting\"}]");

        Region wall = regionRepository.findById(wallId).orElseThrow();
        assertThat(wall.getLabel()).isEqualTo("Skirting");
        assertThat(wall.getCategory()).isEqualTo(RegionCategory.TRIM);
        assertThat(wall.isInPlan()).isFalse();
    }

    /** The whole plan is one gesture. A row that has gone stale — a wall deleted in
     *  another tab — is skipped rather than failing the walls beside it. */
    @Test
    void appliesTheRestOfThePlanAroundARowThatNoLongerResolves() throws Exception {
        savePlan("[{\"regionId\":999999,\"inPlan\":false},"
                + "{\"regionId\":" + ceilingId + ",\"inPlan\":false}]");

        assertThat(regionRepository.findById(ceilingId).orElseThrow().isInPlan()).isFalse();
    }
}
