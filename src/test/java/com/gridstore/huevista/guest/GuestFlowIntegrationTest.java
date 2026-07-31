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

    /**
     * A guest's run is never refused for the SHOP's quota, because by the time a guest is
     * holding a code the shop has already been charged for it.
     *
     * The gate moved to where the shop actually makes the decision: issuing a code
     * reserves a project credit per assigned project, and that reservation is
     * limit-gated. Refusing at segmentation instead meant a walk-in standing at the
     * counter, photo already uploaded, was told the shop was out — after the shop had
     * handed them the code. There is nothing the guest can do about that, and nothing the
     * counter can do quickly either.
     */
    @Test
    void guest_segmentation_is_not_gated_on_the_shops_quota() throws Exception {
        String guestToken = redeemAsGuest();
        String imageId = guestUpload(guestToken);
        String projectId = guestCreateProject(guestToken, imageId);

        mockMvc.perform(post("/api/guest/projects/" + projectId + "/segment")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());
    }

    /**
     * A kiosk walk-in paid for their own project, so the shop's plan gates nothing.
     *
     * The shop here has no subscription at all — the same state that (correctly) blocks
     * the shop-issued code in the test above. A kiosk code must still run: the customer
     * paid at the store link, the money is already taken, and refusing the work because
     * the SHOP's plan lapsed left them out of pocket with no refund path behind it
     * (StorePayment.reversed is never set on that route).
     */
    @Test
    void kiosk_paid_guest_can_segment_even_when_the_shop_has_no_plan() throws Exception {
        CustomerAccessCode kioskCode = codeRepository.findById(codeId).orElseThrow();
        kioskCode.setSelfFunded(true);
        codeRepository.saveAndFlush(kioskCode);

        String guestToken = redeemAsGuest();
        String projectId = guestCreateProject(guestToken, guestUpload(guestToken));

        mockMvc.perform(post("/api/guest/projects/" + projectId + "/segment")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEGMENTING"));
    }

    /** …and the colour board they paid for comes off the code, not the shop's PDF limit. */
    @Test
    void kiosk_paid_guest_gets_a_colour_board_without_touching_the_shops_pdf_quota() throws Exception {
        CustomerAccessCode kioskCode = codeRepository.findById(codeId).orElseThrow();
        kioskCode.setSelfFunded(true);
        codeRepository.saveAndFlush(kioskCode);
        String guestToken = redeemAsGuest();

        mockMvc.perform(get("/api/guest/pdf-allowance")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(1));

        mockMvc.perform(post("/api/guest/pdf-downloads")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.remaining").value(0));

        // Spent — the code paid for one board, not an unlimited supply.
        mockMvc.perform(post("/api/guest/pdf-downloads")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void guest_segmentation_is_allowed_and_not_charged_upfront_when_shop_has_credits() throws Exception {
        // Give the shop owner an active subscription with image AND auto-mask quota —
        // guest runs are always fully automatic, so a plan without auto-mask credits
        // (Starter is manual-masking only) would 402 the guest into the manual fallback.
        billingService.grantTrial(retailerId, Plan.PROFESSIONAL, 14);

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
        org.junit.jupiter.api.Assertions.assertEquals(0, sub.getProjectsUsed());
    }

    @Test
    void shop_scopes_a_code_to_companies_and_guest_redeem_returns_them() throws Exception {
        // Issuing a code names the customer and charges the assigned projects against
        // the owner's monthly image quota, so the shop needs an active plan.
        billingService.grantTrial(retailerId, Plan.PROFESSIONAL, 14);
        // Owner issues a brand-scoped code.
        MvcResult issued = mockMvc.perform(post("/api/organizations/" + orgId + "/access-codes")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Anjali Nair\",\"projectQuota\":1,\"allowedBrands\":[\"Asian Paints\",\"Berger\"]}"))
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

    /**
     * A guest gets every project the shop PAID FOR, not a hardcoded one.
     *
     * The limit was pinned at 1 regardless of the code. Issuing a code for three projects
     * reserves three image credits against the shop's plan, so a customer arriving by the
     * guest route could create one project while the shop had been charged for three — and
     * the two spare credits then sat held with nothing to spend them on.
     */
    @Test
    void guest_gets_as_many_projects_as_the_code_paid_for() throws Exception {
        CustomerAccessCode multi = codeRepository.findById(codeId).orElseThrow();
        multi.setProjectQuota(3);
        multi.setReservedProjects(3);
        codeRepository.saveAndFlush(multi);

        String guestToken = redeemAsGuest();
        for (int i = 0; i < 3; i++) {
            guestCreateProject(guestToken, guestUpload(guestToken));
        }

        // The fourth is refused — three is what the shop assigned.
        String extraImage = guestUpload(guestToken);
        mockMvc.perform(post("/api/guest/projects")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + extraImage + "\"}"))
                .andExpect(status().isPaymentRequired());

        // …and the shop sees all three rooms against the code, not just the first.
        mockMvc.perform(get("/api/access-codes/" + codeId + "/projects")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    /**
     * Re-entry closes once the customer has handed their room to the shop.
     *
     * Re-entry exists for the customer whose phone died mid-visit, and it costs
     * something: the code is 8 characters on a printed slip, and anyone who reads one
     * gets a session into that customer's room — able to see and overwrite the colours
     * they chose — for the code's whole life. "Send to shop" is the customer saying they
     * are done, so it is the natural end of that window.
     */
    @Test
    void guest_reentry_ends_once_the_room_is_sent_to_the_shop() throws Exception {
        String guestToken = redeemAsGuest();
        String projectId = guestCreateProject(guestToken, guestUpload(guestToken));

        // Before handover, re-entry is fine — this is the dead-phone case.
        mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/guest/projects/" + projectId + "/send-to-shop")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isConflict());
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
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(com.gridstore.huevista.auth.model.UserRole.CUSTOMER).build());
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

    @Test
    void guest_and_issuing_shop_can_load_the_guest_image_file() throws Exception {
        // Local-storage mode: image URLs are relative, auth-gated backend paths
        // (S3 mode returns presigned URLs and never hits this endpoint).
        String guestToken = redeemAsGuest();
        MockMultipartFile file = new MockMultipartFile("file", "room.jpg", "image/jpeg", fakeJpegBytes());
        MvcResult up = mockMvc.perform(multipart("/api/guest/images/upload").file(file)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isCreated())
                .andReturn();
        String imageUrl = objectMapper.readTree(up.getResponse().getContentAsString())
                .get("imageUrl").asText();
        org.assertj.core.api.Assertions.assertThat(imageUrl).startsWith("/api/images/files/");

        // The guest can load their own photo (key prefix = their access-code id).
        mockMvc.perform(get(imageUrl).header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        // The issuing shop can load it too — the portal's "view what the guest picked".
        mockMvc.perform(get(imageUrl).header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk());

        // A different shop's owner cannot.
        userRepository.save(User.builder().name("Other Shop").email("other-shop@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String otherToken = login("other-shop@example.com", "password123");
        mockMvc.perform(get(imageUrl).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
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
