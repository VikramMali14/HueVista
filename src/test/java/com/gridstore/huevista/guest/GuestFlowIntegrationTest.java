package com.gridstore.huevista.guest;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.OrgMembership;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.image.service.ClaudeVisionService;
import com.razorpay.RazorpayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class GuestFlowIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean ClaudeVisionService claudeVisionService; // unused by the guest path, but keep context light
    // The queue is Redis-backed and Redis isn't available under test; mock it so the
    // segment request enqueues a no-op instead of failing to connect.
    @MockitoBean com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository orgRepository;
    @Autowired OrgMembershipRepository membershipRepository;
    @Autowired CustomerAccessCodeRepository codeRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BillingService billingService;
    @Autowired SubscriptionRepository subscriptionRepository;

    private static final String CODE = "GUESTAB2";
    private String codeId;
    private String orgId;
    private String retailerId;
    private String retailerToken;

    @BeforeEach
    void setUp() throws Exception {
        User retailer = userRepository.save(User.builder()
                .name("Shop Owner").email("shop@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        retailerId = retailer.getId();

        Organization org = orgRepository.save(Organization.builder()
                .name("Sharda Paints").slug("sharda-paints-guesttest")
                .type(OrgType.RETAILER).owner(retailer).build());
        orgId = org.getId();

        membershipRepository.save(OrgMembership.builder()
                .user(retailer).organization(org).role(OrgMemberRole.OWNER).build());

        CustomerAccessCode code = codeRepository.save(CustomerAccessCode.builder()
                .organization(org).code(CODE).validDays(7)
                .expiresAt(LocalDateTime.now().plusDays(7)).build());
        codeId = code.getId();

        retailerToken = login("shop@example.com", "password123");
    }

    @Test
    void guest_redeems_creates_one_project_and_shop_resolves() throws Exception {
        String guestToken = redeemAsGuest();
        String imageId = guestUpload(guestToken);
        String projectId = guestCreateProject(guestToken, imageId);

        // Second project is blocked — the guest gets exactly one.
        mockMvc.perform(post("/api/guest/projects")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + imageId + "\"}"))
                .andExpect(status().isPaymentRequired());

        // The issuing shop can resolve the guest's project (full view) by the code.
        mockMvc.perform(get("/api/access-codes/" + codeId + "/guest-project")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));
    }

    @Test
    void guest_segmentation_is_blocked_when_shop_has_no_ai_credits() throws Exception {
        String guestToken = redeemAsGuest();
        String imageId = guestUpload(guestToken);
        String projectId = guestCreateProject(guestToken, imageId);

        // The test shop owner was created directly (no trial / subscription), so the
        // shop has no AI quota. Guest AI must be blocked with 402 — the UI then falls
        // back to letting the guest mark walls by hand.
        mockMvc.perform(post("/api/guest/projects/" + projectId + "/segment")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void guest_segmentation_is_allowed_and_not_charged_upfront_when_shop_has_credits() throws Exception {
        // Give the shop owner an active subscription with AI quota.
        billingService.grantTrial(retailerId, Plan.STARTER, 14);

        String guestToken = redeemAsGuest();
        String imageId = guestUpload(guestToken);
        String projectId = guestCreateProject(guestToken, imageId);

        mockMvc.perform(post("/api/guest/projects/" + projectId + "/segment")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEGMENTING"));

        // Decrement-on-success: nothing is charged at request time. The async run can
        // only bill the shop once it actually produces walls, so quota stays untouched here.
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(retailerId, SubscriptionStatus.ACTIVE)
                .orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(0, sub.getAiGenerationsUsed());
    }

    @Test
    void shop_scopes_a_code_to_companies_and_guest_redeem_returns_them() throws Exception {
        // Owner issues a brand-scoped code.
        MvcResult issued = mockMvc.perform(post("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validDays\":7,\"allowedBrands\":[\"Asian Paints\",\"Berger\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedBrands.length()").value(2))
                .andReturn();
        String scopedCode = objectMapper.readTree(issued.getResponse().getContentAsString()).get("code").asText();

        // A guest redeeming that code gets the allowed companies back, so the studio
        // can limit their shade picker to just those brands.
        mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + scopedCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBrands.length()").value(2))
                .andExpect(jsonPath("$.allowedBrands[0]").value("Asian Paints"));
    }

    @Test
    void guest_reentry_ends_when_the_code_expires() throws Exception {
        // Guest re-entry of a redeemed code is allowed only inside the validity
        // window (see guest_can_reenter_the_same_code_and_get_their_project_back);
        // once the code expires, re-entering it must be rejected.
        redeemAsGuest();
        CustomerAccessCode code = codeRepository.findById(codeId).orElseThrow();
        code.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        codeRepository.save(code);

        mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void guest_endpoints_reject_a_normal_user_token() throws Exception {
        userRepository.save(User.builder().name("Plain").email("plain@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String userToken = login("plain@example.com", "password123");

        mockMvc.perform(get("/api/guest/projects")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void guest_can_claim_projects_after_signing_up() throws Exception {
        String guestToken = redeemAsGuest();
        String imageId = guestUpload(guestToken);
        String projectId = guestCreateProject(guestToken, imageId);

        // Public /join signup creates a CUSTOMER — the role whose every project read
        // is gated on an entitlement row. Claiming must therefore establish one, or
        // the projects would be locked ("Your access is not set up") the moment they
        // were claimed. Regression test for exactly that bug.
        userRepository.save(User.builder().name("Walk In").email("walkin@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(com.gridstore.huevista.auth.model.UserRole.CUSTOMER).build());
        String userToken = login("walkin@example.com", "password123");

        mockMvc.perform(post("/api/projects/claim-guest")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestToken\":\"" + guestToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(1));

        // The signed-up CUSTOMER now owns the project AND can actually read it.
        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));

        // The claim also established the entitlement mirroring the guest's access
        // (the claimed project counts as the included one).
        mockMvc.perform(get("/api/me/entitlement")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectAllowance").value(1))
                .andExpect(jsonPath("$.projectsCreated").value(1));
    }

    @Test
    void guest_can_reenter_the_same_code_and_get_their_project_back() throws Exception {
        String firstToken = redeemAsGuest();
        String imageId = guestUpload(firstToken);
        String projectId = guestCreateProject(firstToken, imageId);

        // The phone died / the incognito window closed: re-entering the SAME code
        // while it's still valid mints a fresh token scoped to the same saved work
        // (a burned cookie used to strand the customer at the counter).
        String secondToken = redeemAsGuest();
        mockMvc.perform(get("/api/guest/projects/" + projectId)
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));
    }

    @Test
    void account_consumed_codes_stay_single_use_for_guests() throws Exception {
        // A signed-in user redeems the code onto their account…
        userRepository.save(User.builder().name("Acct").email("acct@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String userToken = login("acct@example.com", "password123");
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk());

        // …after which guest re-entry must NOT open it.
        mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void guest_can_send_the_project_to_the_shop() throws Exception {
        String guestToken = redeemAsGuest();
        String imageId = guestUpload(guestToken);
        String projectId = guestCreateProject(guestToken, imageId);

        mockMvc.perform(post("/api/guest/projects/" + projectId + "/send-to-shop")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentToShopAt").isNotEmpty());

        // Idempotent, and the shop's full view carries the flag.
        mockMvc.perform(post("/api/guest/projects/" + projectId + "/send-to-shop")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentToShopAt").isNotEmpty());
        mockMvc.perform(get("/api/access-codes/" + codeId + "/guest-project")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentToShopAt").isNotEmpty());
    }

    // --- helpers ---

    private String redeemAsGuest() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("guestToken").asText();
    }

    private String guestUpload(String guestToken) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "room.jpg", "image/jpeg", fakeJpegBytes());
        MvcResult r = mockMvc.perform(multipart("/api/guest/images/upload").file(file)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("imageId").asText();
    }

    private String guestCreateProject(String guestToken, String imageId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/guest/projects")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + imageId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }

    private static byte[] fakeJpegBytes() {
        byte[] header = {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        };
        byte[] out = new byte[header.length + 256 + 2];
        System.arraycopy(header, 0, out, 0, header.length);
        out[out.length - 2] = (byte) 0xFF;
        out[out.length - 1] = (byte) 0xD9;
        return out;
    }
}
