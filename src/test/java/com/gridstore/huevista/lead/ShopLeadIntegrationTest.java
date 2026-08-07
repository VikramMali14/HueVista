package com.gridstore.huevista.lead;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.DistributorRetailerLinkRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.lead.model.ShopLead;
import com.gridstore.huevista.lead.repository.ShopLeadRepository;
import com.gridstore.huevista.lead.service.ShopRequestAutoApprovalJob;
import com.gridstore.huevista.notification.EmailSender;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shop-account request funnel: anyone can ask for an account, but only after
 * proving the mailbox, only once per address, and only ever onto the free plan.
 * An admin turns a request into an account in one call; if nobody does, the
 * 24-hour deadline does it for them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ShopLeadIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    /** Mocked so the test can read the 6-digit code back out of the "sent" mail. */
    @MockitoBean
    EmailSender emailSender;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShopLeadRepository leadRepository;
    @Autowired OrganizationRepository orgRepository;
    @Autowired DistributorRetailerLinkRepository distributorLinkRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired ShopRequestAutoApprovalJob autoApprovalJob;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String REQUEST_BODY = """
            {"name":"Priya Mehta","email":"priya@mehtapaints.in","phone":"+919822104476",
             "shopName":"Mehta Paint House","city":"Pune","state":"Maharashtra",
             "password":"counter123","confirmPassword":"counter123",
             "notes":"Busy weekend counter."}
            """;

    // ── The public funnel ─────────────────────────────────────────────────

    @Test
    void a_request_is_invisible_until_the_email_is_verified() throws Exception {
        String requestId = submit(REQUEST_BODY);

        ShopLead lead = leadRepository.findById(requestId).orElseThrow();
        assertThat(lead.getStatus()).isEqualTo(ShopLead.Status.PENDING_EMAIL);
        assertThat(lead.isEmailVerified()).isFalse();
        // No account, no deadline, nothing to approve yet.
        assertThat(userRepository.findByEmail("priya@mehtapaints.in")).isEmpty();
        assertThat(lead.getAutoApproveAt()).isNull();

        // Wrong code is refused and counted; the right one queues the request.
        mockMvc.perform(post("/api/leads/shop/" + requestId + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/leads/shop/" + requestId + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + codeFromEmail("priya@mehtapaints.in") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        ShopLead verified = leadRepository.findById(requestId).orElseThrow();
        assertThat(verified.isEmailVerified()).isTrue();
        assertThat(verified.getAutoApproveAt()).isAfter(LocalDateTime.now().plusHours(23));
        // Still no account — verification queues the request, it does not provision it.
        assertThat(userRepository.findByEmail("priya@mehtapaints.in")).isEmpty();
    }

    /**
     * The password is the requester's own and nothing readable is kept: only a hash,
     * which no response carries and which is dropped from the request row once the
     * account exists.
     */
    @Test
    void the_password_is_stored_only_as_a_hash_and_never_returned() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(submitted.getResponse().getContentAsString()).doesNotContain("counter123");

        ShopLead lead = leadRepository.findAll().get(0);
        assertThat(lead.getPasswordHash()).isNotEqualTo("counter123");
        assertThat(passwordEncoder.matches("counter123", lead.getPasswordHash())).isTrue();
        // Even a whole-object log line cannot spill it.
        assertThat(lead.toString()).doesNotContain(lead.getPasswordHash());

        String adminToken = seedAdminAndLogin();
        verifyRequest(lead.getId(), "priya@mehtapaints.in");
        MvcResult queue = mockMvc.perform(get("/api/admin/leads")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String body = queue.getResponse().getContentAsString();
        assertThat(body).doesNotContain("counter123").doesNotContain("passwordHash");

        // And the shop can actually sign in with it once the account exists.
        approve(adminToken, lead.getId(), null);
        assertThat(login("priya@mehtapaints.in", "counter123")).isNotBlank();
        assertThat(leadRepository.findById(lead.getId()).orElseThrow().getPasswordHash()).isNull();
    }

    @Test
    void the_password_must_be_typed_the_same_way_twice() throws Exception {
        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"priya@mehtapaints.in","shopName":"Mehta Paint House",
                                 "password":"counter123","confirmPassword":"counter124"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(leadRepository.findAll()).isEmpty();
    }

    @Test
    void submission_requires_name_email_shop_and_a_password() throws Exception {
        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.shopName").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ── One free account per mailbox ──────────────────────────────────────

    /**
     * The free tier is per shop, not per attempt. An address that already has an
     * account — or already has one coming — is turned away rather than queued again.
     */
    @Test
    void an_email_that_already_has_a_shop_cannot_request_another() throws Exception {
        String adminToken = seedAdminAndLogin();
        String requestId = submit(REQUEST_BODY);
        verifyRequest(requestId, "priya@mehtapaints.in");

        // Second attempt while the first is queued.
        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());

        approve(adminToken, requestId, null);

        // And after the account exists.
        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
        assertThat(userRepository.findByEmail("priya@mehtapaints.in")).isPresent();
    }

    @Test
    void an_email_with_any_existing_account_is_refused() throws Exception {
        userRepository.save(User.builder().name("Cust").email("priya@mehtapaints.in")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());

        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
    }

    // ── The admin queue ───────────────────────────────────────────────────

    @Test
    void the_queue_is_admin_only() throws Exception {
        mockMvc.perform(get("/api/admin/leads")).andExpect(status().isUnauthorized());

        userRepository.save(User.builder().name("Cust").email("cust@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());
        mockMvc.perform(get("/api/admin/leads")
                        .header("Authorization", "Bearer " + login("cust@example.com", "password123")))
                .andExpect(status().isForbidden());
    }

    /**
     * One click: the request's own details become an account on the FREE plan, filed
     * under whichever distributor the admin chose.
     */
    @Test
    void an_admin_creates_the_account_in_one_call_under_a_chosen_distributor() throws Exception {
        String adminToken = seedAdminAndLogin();
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dist Owner","email":"dist@example.com","password":"password123",
                                 "companyName":"Western Paints Co"}"""))
                .andExpect(status().isCreated());
        User distributor = userRepository.findByEmail("dist@example.com").orElseThrow();
        Organization distOrg =
                orgRepository.findByOwnerIdAndType(distributor.getId(), OrgType.DISTRIBUTOR).get(0);

        String requestId = submit(REQUEST_BODY);
        verifyRequest(requestId, "priya@mehtapaints.in");
        approve(adminToken, requestId, distOrg.getId());

        User shop = userRepository.findByEmail("priya@mehtapaints.in").orElseThrow();
        assertThat(shop.getRole()).isEqualTo(UserRole.RETAILER);
        Organization shopOrg = orgRepository.findByOwnerIdAndType(shop.getId(), OrgType.RETAILER).get(0);
        assertThat(shopOrg.getName()).isEqualTo("Mehta Paint House");
        assertThat(distributorLinkRepository
                .existsByDistributorIdAndRetailerId(distOrg.getId(), shopOrg.getId())).isTrue();

        // Free plan, always — a request cannot produce paid quota.
        assertThat(subscriptionRepository.findAll())
                .filteredOn(s -> s.getUser().getId().equals(shop.getId()))
                .allMatch(s -> s.getPlan() == Plan.FREE);
    }

    /** No distributor named means the house one, so a shop is never left dangling. */
    @Test
    void approving_without_a_distributor_files_the_shop_under_the_house_one() throws Exception {
        String adminToken = seedAdminAndLogin();
        String requestId = submit(REQUEST_BODY);
        verifyRequest(requestId, "priya@mehtapaints.in");
        approve(adminToken, requestId, null);

        User shop = userRepository.findByEmail("priya@mehtapaints.in").orElseThrow();
        Organization shopOrg = orgRepository.findByOwnerIdAndType(shop.getId(), OrgType.RETAILER).get(0);
        Organization house = orgRepository.findBySlug("huevista-direct").orElseThrow();
        assertThat(distributorLinkRepository
                .existsByDistributorIdAndRetailerId(house.getId(), shopOrg.getId())).isTrue();
    }

    @Test
    void an_unverified_request_cannot_be_approved() throws Exception {
        String adminToken = seedAdminAndLogin();
        String requestId = submit(REQUEST_BODY);

        mockMvc.perform(post("/api/admin/leads/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
        assertThat(userRepository.findByEmail("priya@mehtapaints.in")).isEmpty();
    }

    @Test
    void dismissing_creates_nothing_and_drops_the_stored_hash() throws Exception {
        String adminToken = seedAdminAndLogin();
        String requestId = submit(REQUEST_BODY);
        verifyRequest(requestId, "priya@mehtapaints.in");

        mockMvc.perform(post("/api/admin/leads/" + requestId + "/dismiss")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        assertThat(userRepository.findByEmail("priya@mehtapaints.in")).isEmpty();
        ShopLead dismissed = leadRepository.findById(requestId).orElseThrow();
        assertThat(dismissed.getPasswordHash()).isNull();
        assertThat(dismissed.getAutoApproveAt()).isNull();
    }

    /**
     * The acknowledgement mail promises an account "by this time tomorrow, either way".
     * A dismissal that says nothing leaves the applicant waiting on it forever.
     */
    @Test
    void a_dismissed_request_is_told_it_is_not_going_ahead() throws Exception {
        String adminToken = seedAdminAndLogin();
        String requestId = submit(REQUEST_BODY);
        verifyRequest(requestId, "priya@mehtapaints.in");

        mockMvc.perform(post("/api/admin/leads/" + requestId + "/dismiss")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String body = lastEmailTo("priya@mehtapaints.in");
        assertThat(body).contains("not going ahead");
        // The address is genuinely free to try again (dismiss drops the hash, it does not
        // block the email), so the mail has to say so rather than read as a permanent no.
        assertThat(body).contains("ask again");
    }

    /**
     * An unverified request was never promised anything — it only ever got a code it
     * ignored — so dismissing it must not put a second mail in a mailbox that may not
     * have asked for the first.
     */
    @Test
    void dismissing_an_unverified_request_emails_nobody() throws Exception {
        String adminToken = seedAdminAndLogin();
        String requestId = submit(REQUEST_BODY);
        // Deliberately NOT verified: the only mail so far is the verification code.
        clearInvocations(emailSender);

        mockMvc.perform(post("/api/admin/leads/" + requestId + "/dismiss")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        verify(emailSender, never()).send(eq("priya@mehtapaints.in"), anyString(), anyString());
    }

    // ── The 24-hour deadline ──────────────────────────────────────────────

    /** A shop that did everything asked of it does not wait on an admin being awake. */
    @Test
    void an_untouched_request_provisions_itself_after_the_deadline() throws Exception {
        seedAdminAndLogin(); // an admin must exist to own the house distributor
        String requestId = submit(REQUEST_BODY);
        verifyRequest(requestId, "priya@mehtapaints.in");

        // Nothing yet — the deadline is a day out.
        assertThat(autoApprovalJob.provisionOverdueRequests()).isZero();

        ShopLead lead = leadRepository.findById(requestId).orElseThrow();
        lead.setAutoApproveAt(LocalDateTime.now().minusMinutes(1));
        leadRepository.saveAndFlush(lead);

        assertThat(autoApprovalJob.provisionOverdueRequests()).isEqualTo(1);

        User shop = userRepository.findByEmail("priya@mehtapaints.in").orElseThrow();
        assertThat(shop.getRole()).isEqualTo(UserRole.RETAILER);
        ShopLead approved = leadRepository.findById(requestId).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ShopLead.Status.APPROVED);
        assertThat(approved.getApprovedByUserId()).isNull(); // the deadline, not a person
        assertThat(approved.getAutoApproveAt()).isNull();
        // A second sweep must not create a second account.
        assertThat(autoApprovalJob.provisionOverdueRequests()).isZero();
    }

    /**
     * Requests carried over from the old call-back funnel have no password and no
     * verified address, so the deadline leaves them alone.
     */
    @Test
    void legacy_requests_are_never_provisioned_automatically() {
        leadRepository.save(ShopLead.builder()
                .name("Owner").email("owner@example.in").shopName("Shree Colours")
                .status(ShopLead.Status.AWAITING_APPROVAL)
                .autoApproveAt(LocalDateTime.now().minusDays(2))
                .build());

        assertThat(autoApprovalJob.provisionOverdueRequests()).isZero();
        assertThat(userRepository.findByEmail("owner@example.in")).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String submit(String body) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        return json.get("requestId").asText();
    }

    private void verifyRequest(String requestId, String email) throws Exception {
        mockMvc.perform(post("/api/leads/shop/" + requestId + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + codeFromEmail(email) + "\"}"))
                .andExpect(status().isOk());
    }

    private void approve(String adminToken, String requestId, String distributorOrgId) throws Exception {
        String body = distributorOrgId == null ? "{}"
                : "{\"distributorOrgId\":\"" + distributorOrgId + "\"}";
        mockMvc.perform(post("/api/admin/leads/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** The body of the most recent mail "sent" to {@code to}. */
    private String lastEmailTo(String to) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(to), anyString(), body.capture());
        return body.getAllValues().get(body.getAllValues().size() - 1);
    }

    /** Pull the 6-digit code out of the verification mail the service "sent". */
    private String codeFromEmail(String to) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(to), anyString(), body.capture());
        for (int i = body.getAllValues().size() - 1; i >= 0; i--) {
            Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(body.getAllValues().get(i));
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("No verification code was emailed to " + to);
    }

    private String seedAdminAndLogin() throws Exception {
        userRepository.save(User.builder().name("Root").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
        return login("root@example.com", "password123");
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
