package com.gridstore.huevista.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a CUSTOMER's project is sold with: one colour board, five colours on it, and the
 * render on the other side of the download.
 *
 * <p>Both numbers are asserted at their SHIPPING defaults — no {@code @TestPropertySource}
 * pinning them — because this suite is about the deal a customer is offered, and that deal
 * is the pair of numbers themselves. Its sibling {@code ProjectClosureIntegrationTest} does
 * the opposite on purpose: it pins the cap because it is testing what closing DOES, not
 * what a project is sold with.
 *
 * <p>The account holds no subscription, which is the whole point — a customer never does.
 * The project is covered by its own paid window, the way a room bought at the till is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class CustomerBoardLimitsIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String token;
    private String projectId;

    /** A board of {@code n} pages, each one a different combination of the same surfaces. */
    private static String board(int n) {
        StringBuilder json = new StringBuilder("{\"pages\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) json.append(',');
            json.append("""
                    {"title":"Option %d","shades":[
                      {"regionLabel":"Main wall","shadeCode":"AP-%d","shadeName":"Shade %d","hex":"#e8d5b0"}
                    ]}""".formatted(i + 1, i, i + 1));
        }
        return json.append("]}").toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        User user = userRepository.save(User.builder()
                .name("Walk-in Customer")
                .email("board-customer@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .emailVerified(true)
                .phoneNumber("+919886547399")
                .phoneVerified(true)
                .build());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"board-customer@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readValue(login.getResponse().getContentAsString(),
                AuthResponse.class).getAccessToken();

        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(user)
                .originalFilename("room.jpg")
                .storageKey("test/room.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .imageType(ImageType.INDOOR)
                .build());

        projectId = projectRepository.save(Project.builder()
                .user(user)
                .image(image)
                .name("Customer room")
                .status(ProjectStatus.SEGMENTED)
                // Bought outright at the till: the project's own window is what covers it,
                // since a customer holds no plan for a subscription rail to find.
                .purchasedAt(LocalDateTime.now())
                .accessExpiresAt(LocalDateTime.now().plusDays(30))
                .build()).getId();
    }

    private MvcResult postBoard(int pages) throws Exception {
        return mockMvc.perform(post("/api/projects/" + projectId + "/colour-boards")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(board(pages)))
                .andReturn();
    }

    // ─── Five colours, not sixteen ───────────────────────────────────────────

    /**
     * The allowance a customer's studio sizes its tray from.
     *
     * Sixteen used to arrive here, and nobody chose sixteen: with no plan to charge, a
     * customer gets the "unmetered" allowance, and that helper carries ENTERPRISE's
     * per-document cap in order to say "nothing is being billed", not to size a sheet.
     */
    @Test
    void aCustomersBoardIsQuotedAtFiveColours() throws Exception {
        mockMvc.perform(get("/api/billing/pdf-allowance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagesPerPdf").value(5))
                // Still no monthly counter — the cap is the project, not a plan.
                .andExpect(jsonPath("$.unlimited").value(true));
    }

    @Test
    void aBoardOfFiveColoursIsAccepted() throws Exception {
        assertThat(postBoard(5).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * The studio caps its own tray at the quoted five, so this only fires on a client that
     * ignored the allowance — and when it does, nothing is recorded and the project is
     * neither advanced nor closed.
     */
    @Test
    void aBoardOfSixColoursIsRefusedAndCostsTheProjectNothing() throws Exception {
        assertThat(postBoard(6).getResponse().getStatus()).isEqualTo(409);

        Project after = projectRepository.findById(projectId).orElseThrow();
        assertThat(after.getColourBoardsUsed()).isZero();
        assertThat(after.isClosed()).isFalse();
    }

    // ─── One board, and the job is finished ──────────────────────────────────

    @Test
    void aCustomersProjectIsSoldWithOneBoard() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardsAllowed").value(1))
                .andExpect(jsonPath("$.boardsUsed").value(0))
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void downloadingTheBoardClosesTheProject() throws Exception {
        assertThat(postBoard(5).getResponse().getContentAsString())
                .contains("\"boardsUsed\":1")
                .contains("\"boardsAllowed\":1")
                .contains("\"closed\":true");

        assertThat(projectRepository.findById(projectId).orElseThrow().isClosed()).isTrue();
    }

    /** 402 — the project closed itself on that board, so the view-only rules govern now. */
    @Test
    void aSecondBoardIsRefused() throws Exception {
        postBoard(5);

        assertThat(postBoard(1).getResponse().getStatus()).isEqualTo(402);
    }

    @Test
    void theClosedProjectKeepsItsCombos() throws Exception {
        postBoard(5);

        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.boardsUsed").value(1))
                .andExpect(jsonPath("$.boardsAllowed").value(1))
                .andExpect(jsonPath("$.rendersUsed").value(0));

        mockMvc.perform(get("/api/projects/" + projectId + "/combos")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }
}
