package com.gridstore.huevista.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.razorpay.RazorpayClient;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An administrator runs the platform and is never billed by it.
 *
 * Every billing endpoint used to answer an admin as though they were a lapsed customer:
 * {@code /subscriptions/current} 404'd because there is no subscription row, and
 * {@code /pdf-allowance} 402'd for the same reason. Both answers were technically true
 * and completely useless — the console rendered "subscribe to continue" to the person who
 * administers the subscriptions, and the browser filled with failed requests on every
 * page load.
 *
 * The fix is an exemption stated once ({@code UnbilledAccounts}) and consulted by each
 * gate, rather than granting admins a real comped plan — a plan would expire, renew, and
 * show up in revenue reports as money nobody paid.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AdminIsNotBilledIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminToken() throws Exception {
        userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
        return tokenFor("root@example.com");
    }

    private String tokenFor(String email) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(login.getResponse().getContentAsString(),
                AuthResponse.class).getAccessToken();
    }

    @Test
    void an_admin_asking_for_their_subscription_gets_an_answer_not_a_404() throws Exception {
        mockMvc.perform(get("/api/billing/subscriptions/current")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                // The flag is the point: a client must be able to tell "exempt" from
                // "on a plan", so it offers neither a cancel nor an upgrade.
                .andExpect(jsonPath("$.unbilled").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // Nothing to renew, nothing to cancel, no period to run out.
                .andExpect(jsonPath("$.currentPeriodEnd").doesNotExist())
                .andExpect(jsonPath("$.trial").value(false));
    }

    @Test
    void an_admin_can_download_colour_boards_without_a_plan() throws Exception {
        mockMvc.perform(get("/api/billing/pdf-allowance")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited").value(true))
                // The per-document cap still applies — it guards browser memory, not revenue.
                .andExpect(jsonPath("$.imagesPerPdf").value(
                        com.gridstore.huevista.billing.model.Plan.ENTERPRISE.getPdfImageLimit()));
    }

    @Test
    void charging_a_download_to_an_admin_does_not_meter_anything() throws Exception {
        String token = adminToken();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/billing/pdf-downloads")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unlimited").value(true))
                    .andExpect(jsonPath("$.used").value(0));
        }
    }

    /**
     * A retailer with no plan still gets the refusal. The exemption is for the role that
     * runs the platform, not a hole anyone lands in by having no subscription.
     */
    @Test
    void a_shop_with_no_plan_is_still_refused() throws Exception {
        userRepository.save(User.builder()
                .name("Priya").email("shop@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.RETAILER).build());

        mockMvc.perform(get("/api/billing/pdf-allowance")
                        .header("Authorization", "Bearer " + tokenFor("shop@example.com")))
                .andExpect(status().isPaymentRequired());
    }

    /** A mistyped path is the caller's error, not ours — and must not log as a server fault. */
    @Test
    void an_unknown_endpoint_is_a_404() throws Exception {
        mockMvc.perform(get("/api/billing/no-such-thing")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
    }
}
