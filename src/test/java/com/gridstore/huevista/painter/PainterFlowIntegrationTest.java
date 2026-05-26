package com.gridstore.huevista.painter;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.painter.dto.*;
import com.gridstore.huevista.painter.model.PaintJobStatus;
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.razorpay.RazorpayClient;
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

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class PainterFlowIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ImageRepository imageRepository;
    @Autowired ProjectRepository projectRepository;

    private String retailerOwnerToken;
    private String retailerOwnerId;
    private String painterToken;
    private String painterId;
    private String customerId;
    private String retailerOrgId;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        // 1. Three users: retailer owner, painter (initially CUSTOMER role), customer
        retailerOwnerToken = registerAndLogin("retailer-owner@example.com", "Retailer Owner");
        retailerOwnerId = userRepository.findByEmail("retailer-owner@example.com").orElseThrow().getId();

        painterToken = registerAndLogin("painter@example.com", "Suresh Painter");
        painterId = userRepository.findByEmail("painter@example.com").orElseThrow().getId();

        User customer = userRepository.save(User.builder()
                .name("Walk-in Customer")
                .email("customer@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .emailVerified(false)
                .build());
        customerId = customer.getId();

        // 2. Retailer org owned by retailer-owner
        Organization retailer = organizationRepository.save(Organization.builder()
                .name("Sharda Paints")
                .slug("sharda-paints")
                .type(OrgType.RETAILER)
                .owner(userRepository.findById(retailerOwnerId).orElseThrow())
                .build());
        retailerOrgId = retailer.getId();

        // 3. Existing project for the customer (skip upload/segment, seed directly)
        UploadedImage image = imageRepository.save(UploadedImage.builder()
                .user(customer)
                .originalFilename("living-room.jpg")
                .storageKey("test/living-room.jpg")
                .contentType("image/jpeg")
                .fileSize(2048L)
                .imageType(ImageType.INDOOR)
                .build());
        Project project = projectRepository.save(Project.builder()
                .user(customer)
                .image(image)
                .name("Belgavi 3BHK · Living Room")
                .build());
        projectId = project.getId();
    }

    // ── 1. Painter invitation + redeem ──

    @Test
    void retailer_generates_invitation_painter_redeems_and_is_linked() throws Exception {
        // Retailer generates an invitation
        GeneratePainterInvitationRequest gen = new GeneratePainterInvitationRequest();
        gen.setValidDays(7);
        MvcResult genResult = mockMvc.perform(
                        post("/api/organizations/{retailerOrgId}/painter-invitations", retailerOrgId)
                                .header("Authorization", "Bearer " + retailerOwnerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andReturn();
        String code = objectMapper.readTree(genResult.getResponse().getContentAsString())
                .get("code").asText();

        // Painter (currently role=CUSTOMER from registerAndLogin) redeems
        RedeemPainterInvitationRequest redeem = new RedeemPainterInvitationRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/painter-invitations/redeem")
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.painterId").value(painterId))
                .andExpect(jsonPath("$.retailerId").value(retailerOrgId));

        // Re-redeeming the same code is rejected
        mockMvc.perform(post("/api/painter-invitations/redeem")
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());

        // Painter role was upgraded
        User painter = userRepository.findById(painterId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(painter.getRole()).isEqualTo(UserRole.PAINTER);
    }

    @Test
    void unknown_invitation_code_is_rejected() throws Exception {
        RedeemPainterInvitationRequest redeem = new RedeemPainterInvitationRequest();
        redeem.setCode("FAKE1234");
        mockMvc.perform(post("/api/painter-invitations/redeem")
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void non_retailer_cannot_generate_invitations() throws Exception {
        GeneratePainterInvitationRequest gen = new GeneratePainterInvitationRequest();
        gen.setValidDays(7);
        mockMvc.perform(post("/api/organizations/{retailerOrgId}/painter-invitations", retailerOrgId)
                        .header("Authorization", "Bearer " + painterToken)  // not the owner
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isForbidden());
    }

    // ── 2. Profile management ──

    @Test
    void painter_profile_404_before_redeem_then_get_after() throws Exception {
        // Before redeem: 404
        mockMvc.perform(get("/api/painters/me")
                        .header("Authorization", "Bearer " + painterToken))
                .andExpect(status().isNotFound());

        // Redeem
        String code = generateInvitationCode();
        RedeemPainterInvitationRequest redeem = new RedeemPainterInvitationRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/painter-invitations/redeem")
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk());

        // After redeem: 200 with profile
        mockMvc.perform(get("/api/painters/me")
                        .header("Authorization", "Bearer " + painterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(painterId))
                .andExpect(jsonPath("$.active").value(true));

        // Update profile
        UpdatePainterProfileRequest upd = new UpdatePainterProfileRequest();
        upd.setPhone("+91 9876543210");
        upd.setServiceAreas(List.of("Belgavi", "Hubballi"));
        upd.setSpecialties(List.of("interior", "decorative"));
        upd.setYearsExperience(12);
        upd.setDayRateInr(new BigDecimal("850.00"));
        mockMvc.perform(put("/api/painters/me")
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+91 9876543210"))
                .andExpect(jsonPath("$.yearsExperience").value(12));
    }

    // ── 3. Job lifecycle: create → accept → start → complete ──

    @Test
    void full_job_lifecycle_create_accept_start_complete() throws Exception {
        linkPainterToRetailer();

        // Retailer creates the job, assigning the painter
        CreatePaintJobRequest create = new CreatePaintJobRequest();
        create.setProjectId(projectId);
        create.setRetailerId(retailerOrgId);
        create.setPainterId(painterId);
        create.setSiteAddress("12 Vidya Nagar, Belgavi");
        create.setEstimatedAreaSqft(new BigDecimal("420.00"));
        create.setEstimatedPaintLiters(new BigDecimal("7.00"));
        create.setNotes("Two coats throughout. Trim in royale.");

        MvcResult createResult = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.painterId").value(painterId))
                .andReturn();
        String jobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // Painter accepts with quote
        AcceptPaintJobRequest accept = new AcceptPaintJobRequest();
        accept.setQuotedAmountInr(new BigDecimal("9500.00"));
        accept.setEstimatedDays(3);
        mockMvc.perform(post("/api/jobs/{jobId}/accept", jobId)
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accept)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.quotedAmountInr").value(9500.00));

        // Painter starts the work
        mockMvc.perform(post("/api/jobs/{jobId}/start", jobId)
                        .header("Authorization", "Bearer " + painterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // Painter completes the work
        mockMvc.perform(post("/api/jobs/{jobId}/complete", jobId)
                        .header("Authorization", "Bearer " + painterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void painter_can_decline_then_retailer_sees_status() throws Exception {
        linkPainterToRetailer();
        String jobId = createJob();

        DeclinePaintJobRequest decline = new DeclinePaintJobRequest();
        decline.setReason("Booked through this month — can't take it on.");
        mockMvc.perform(post("/api/jobs/{jobId}/decline", jobId)
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decline)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        // Retailer sees the declined job in their list
        mockMvc.perform(get("/api/jobs/by-retailer/{retailerOrgId}", retailerOrgId)
                        .header("Authorization", "Bearer " + retailerOwnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DECLINED"))
                .andExpect(jsonPath("$[0].declineReason").value(decline.getReason()));
    }

    @Test
    void cannot_assign_unlinked_painter() throws Exception {
        // Painter not linked to retailer — assignment should fail
        CreatePaintJobRequest create = new CreatePaintJobRequest();
        create.setProjectId(projectId);
        create.setRetailerId(retailerOrgId);
        create.setPainterId(painterId);

        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void painter_cannot_accept_a_job_thats_not_theirs() throws Exception {
        linkPainterToRetailer();
        String jobId = createJob();

        // Register a SECOND painter who hasn't been assigned this job
        String otherToken = registerAndLogin("other-painter@example.com", "Other Painter");

        AcceptPaintJobRequest accept = new AcceptPaintJobRequest();
        accept.setQuotedAmountInr(new BigDecimal("9500.00"));
        accept.setEstimatedDays(3);
        mockMvc.perform(post("/api/jobs/{jobId}/accept", jobId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accept)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_can_view_and_cancel_their_job() throws Exception {
        linkPainterToRetailer();
        String jobId = createJob();

        String customerToken = loginAs("customer@example.com");

        // Customer sees the job
        mockMvc.perform(get("/api/jobs/mine/customer")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(jobId));

        // Customer cancels
        mockMvc.perform(post("/api/jobs/{jobId}/cancel", jobId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Holding off till next month\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaintJobStatus.CANCELLED.name()));
    }

    // ── helpers ──

    private String generateInvitationCode() throws Exception {
        GeneratePainterInvitationRequest gen = new GeneratePainterInvitationRequest();
        gen.setValidDays(7);
        MvcResult res = mockMvc.perform(
                        post("/api/organizations/{retailerOrgId}/painter-invitations", retailerOrgId)
                                .header("Authorization", "Bearer " + retailerOwnerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(gen)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("code").asText();
    }

    private void linkPainterToRetailer() throws Exception {
        String code = generateInvitationCode();
        RedeemPainterInvitationRequest redeem = new RedeemPainterInvitationRequest();
        redeem.setCode(code);
        mockMvc.perform(post("/api/painter-invitations/redeem")
                        .header("Authorization", "Bearer " + painterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redeem)))
                .andExpect(status().isOk());
    }

    private String createJob() throws Exception {
        CreatePaintJobRequest create = new CreatePaintJobRequest();
        create.setProjectId(projectId);
        create.setRetailerId(retailerOrgId);
        create.setPainterId(painterId);
        create.setEstimatedAreaSqft(new BigDecimal("420.00"));
        create.setEstimatedPaintLiters(new BigDecimal("7.00"));
        MvcResult res = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + retailerOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private String registerAndLogin(String email, String name) throws Exception {
        userRepository.save(User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.RETAILER)
                .emailVerified(false)
                .build());
        return loginAs(email);
    }

    private String loginAs(String email) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse authResp = objectMapper.readValue(res.getResponse().getContentAsString(), AuthResponse.class);
        return authResp.getAccessToken();
    }

    @SuppressWarnings("unused") // kept for clarity when extending tests
    private static PainterLinkStatus parseStatus(String s) {
        return PainterLinkStatus.valueOf(s);
    }
}
