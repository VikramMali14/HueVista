package com.gridstore.huevista.maskreport;

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
import com.gridstore.huevista.maskreport.model.MaskReportStatus;
import com.gridstore.huevista.maskreport.repository.MaskReportRepository;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole loop, over HTTP: a user reports a run that came out wrong, an admin
 * finds it in the queue and closes it — and nobody else can read it on the way.
 *
 * The last part is the one worth a wire-level test. These reports carry another
 * customer's room name, their e-mail and their words, so "only ADMIN sees the
 * queue" has to hold at the endpoint rather than only in the console that calls it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class MaskReportIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired MaskReportRepository maskReportRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String userToken;
    private String adminToken;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        User owner = userRepository.save(User.builder()
                .name("Asha Rao")
                .email("mask-reporter@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneNumber("+919886547321")
                .phoneVerified(true)
                .build());
        subscriptionRepository.save(Subscription.builder()
                .user(owner)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .trial(true)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(14))
                .projectsUsed(0)
                .projectsLimit(Plan.PROFESSIONAL.getMonthlyProjectLimit())
                .build());
        userToken = login("mask-reporter@example.com");

        userRepository.save(User.builder()
                .name("Platform Admin")
                .email("mask-admin@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN)
                .emailVerified(true)
                .build());
        adminToken = login("mask-admin@example.com");

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(owner)
                .originalFilename("bedroom.jpg")
                .storageKey("test/bedroom.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());

        // A run that "succeeded": SEGMENTED, cleaned image written, regions stored —
        // and, as far as anything server-side can tell, indistinguishable from a good
        // one. That is exactly the state a report has to be raisable from.
        Project project = projectRepository.save(Project.builder()
                .user(owner)
                .image(image)
                .name("Front bedroom")
                .status(ProjectStatus.SEGMENTED)
                .maskMode("AUTO")
                .build());
        project.setCleanedImageStorageKey("test/bedroom-cleaned.jpg");
        projectId = projectRepository.save(project).getId();
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
                .getAccessToken();
    }

    private static String body(String issues, String note) {
        return "{\"issues\":[" + issues + "]" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    @Test
    void a_user_reports_a_bad_run_and_an_admin_works_it() throws Exception {
        MvcResult filed = mockMvc.perform(post("/api/projects/" + projectId + "/mask-reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"MASK_NOT_GENERATED_PROPERLY\",\"IMAGE_NOT_CLEANED_PROPERLY\"",
                                "the ceiling was painted as a wall")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.issues.length()").value(2))
                // The receipt is the reporter's own words back — never anyone else's
                // contact details, and never the admin's handling notes.
                .andExpect(jsonPath("$.reporterEmail").doesNotExist())
                .andExpect(jsonPath("$.adminNote").doesNotExist())
                .andReturn();
        String reportId = objectMapper.readTree(filed.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/admin/mask-reports")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reportId))
                .andExpect(jsonPath("$[0].projectName").value("Front bedroom"))
                .andExpect(jsonPath("$[0].reporterEmail").value("mask-reporter@example.com"))
                .andExpect(jsonPath("$[0].note").value("the ceiling was painted as a wall"))
                // The snapshot of the reported run, which a later re-run would erase
                // from the project itself.
                .andExpect(jsonPath("$[0].projectStatus").value("SEGMENTED"))
                .andExpect(jsonPath("$[0].hadCleanedImage").value(true));

        mockMvc.perform(patch("/api/admin/mask-reports/" + reportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"adminNote\":\"re-ran, masks fine now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedByName").value("Platform Admin"));

        // A worklist that opens onto everything ever closed stops being read.
        mockMvc.perform(get("/api/admin/mask-reports")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/admin/mask-reports?includeResolved=true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reportId));
    }

    @Test
    void pressing_report_twice_updates_the_open_one_rather_than_stacking_duplicates() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/mask-reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"MASK_NOT_GENERATED_PROPERLY\"", "first go")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/projects/" + projectId + "/mask-reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"MASK_NOT_GENERATED_PROPERLY\"", "still wrong after the re-run")))
                .andExpect(status().isCreated());

        // One room, one complaint — not two tickets to read and close separately.
        assertThat(maskReportRepository.count()).isEqualTo(1);
        assertThat(maskReportRepository.findAll().get(0).getNote())
                .isEqualTo("still wrong after the re-run");
    }

    @Test
    void a_report_that_names_no_problem_is_refused() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/mask-reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issues\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(maskReportRepository.count()).isZero();
    }

    @Test
    void someone_elses_project_cannot_be_reported() throws Exception {
        User stranger = userRepository.save(User.builder()
                .name("Stranger")
                .email("mask-stranger@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .build());
        subscriptionRepository.save(Subscription.builder()
                .user(stranger)
                .plan(Plan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .trial(true)
                .currentPeriodStart(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(14))
                .projectsUsed(0)
                .projectsLimit(Plan.PROFESSIONAL.getMonthlyProjectLimit())
                .build());

        mockMvc.perform(post("/api/projects/" + projectId + "/mask-reports")
                        .header("Authorization", "Bearer " + login("mask-stranger@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"MASK_NOT_GENERATED_PROPERLY\"", null)))
                .andExpect(status().isNotFound());

        assertThat(maskReportRepository.count()).isZero();
    }

    /**
     * The queue carries other customers' room names, e-mail addresses and words.
     * A non-admin reaching it would be a data leak, not a UI slip, so the refusal
     * is pinned at the endpoint rather than trusted to the console.
     */
    @Test
    void the_queue_is_closed_to_everyone_but_admins() throws Exception {
        mockMvc.perform(get("/api/admin/mask-reports")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/mask-reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_reopened_report_no_longer_claims_to_have_been_resolved() throws Exception {
        MvcResult filed = mockMvc.perform(post("/api/projects/" + projectId + "/mask-reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"OTHER\"", "colours look odd")))
                .andExpect(status().isCreated())
                .andReturn();
        String reportId = objectMapper.readTree(filed.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(patch("/api/admin/mask-reports/" + reportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/mask-reports/" + reportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.resolvedByName").doesNotExist());

        assertThat(maskReportRepository.findAll().get(0).getStatus())
                .isEqualTo(MaskReportStatus.IN_REVIEW);
    }
}
