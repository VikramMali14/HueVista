package com.gridstore.huevista.paint;

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

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Shade-code scheme: the shop's ONE pattern (prefix / inserted pair / suffix)
 * for customer-facing shade codes. Portal CRUD by the shop owner, and studio
 * reads via /api/me/shade-code-scheme for every principal type that visualises
 * under the shop — retailer staff and guests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ShadeCodeSchemeIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository orgRepository;
    @Autowired OrgMembershipRepository membershipRepository;
    @Autowired CustomerAccessCodeRepository codeRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String CODE = "SCHEMEA1";

    private String orgId;
    private String retailerToken;

    @BeforeEach
    void setUp() throws Exception {
        User retailer = userRepository.save(User.builder()
                .name("Shop Owner").email("shop-scheme@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());

        Organization org = orgRepository.save(Organization.builder()
                .name("Sharda Paints").slug("sharda-paints-schemetest")
                .type(OrgType.RETAILER).owner(retailer).build());
        orgId = org.getId();

        membershipRepository.save(OrgMembership.builder()
                .user(retailer).organization(org).role(OrgMemberRole.OWNER).build());

        codeRepository.save(CustomerAccessCode.builder()
                .organization(org).code(CODE).validDays(7)
                .expiresAt(LocalDateTime.now().plusDays(7)).build());

        retailerToken = login("shop-scheme@example.com", "password123");
    }

    @Test
    void owner_sets_reads_and_clears_the_scheme() throws Exception {
        // No scheme yet — every part comes back empty, not 404.
        mockMvc.perform(get("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value(""))
                .andExpect(jsonPath("$.infix").value(""))
                .andExpect(jsonPath("$.suffix").value(""));

        // Set — parts are trimmed and uppercased.
        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"ab\",\"infix\":\"xy\",\"suffix\":\"cd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("AB"))
                .andExpect(jsonPath("$.infix").value("XY"))
                .andExpect(jsonPath("$.suffix").value("CD"));

        // Update in place (no duplicate row) and read back.
        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"SP\",\"infix\":\"\",\"suffix\":\"9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("SP"))
                .andExpect(jsonPath("$.infix").value(""))
                .andExpect(jsonPath("$.suffix").value("9"));

        // All-empty clears it.
        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"\",\"infix\":\"\",\"suffix\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value(""));

        mockMvc.perform(get("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suffix").value(""));
    }

    @Test
    void scheme_parts_are_length_and_charset_limited() throws Exception {
        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"TOOLONG\",\"infix\":\"\",\"suffix\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"A-B\",\"infix\":\"\",\"suffix\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"\",\"infix\":\"XYZ\",\"suffix\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_stranger_cannot_manage_or_read_another_shops_scheme() throws Exception {
        userRepository.save(User.builder().name("Other").email("other-scheme@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String otherToken = login("other-scheme@example.com", "password123");

        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"HA\",\"infix\":\"\",\"suffix\":\"\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void guest_on_a_shop_code_reads_the_shops_scheme_and_a_stranger_reads_empty() throws Exception {
        mockMvc.perform(put("/api/organizations/" + orgId + "/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefix\":\"AB\",\"infix\":\"XY\",\"suffix\":\"CD\"}"))
                .andExpect(status().isOk());

        MvcResult r = mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String guestToken = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("guestToken").asText();

        mockMvc.perform(get("/api/me/shade-code-scheme")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("AB"))
                .andExpect(jsonPath("$.infix").value("XY"))
                .andExpect(jsonPath("$.suffix").value("CD"));

        // The retailer's own staff read the same scheme through /api/me.
        mockMvc.perform(get("/api/me/shade-code-scheme")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("AB"));

        // An unrelated user gets the empty scheme, not an error.
        userRepository.save(User.builder().name("Nobody").email("nobody-scheme@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String strangerToken = login("nobody-scheme@example.com", "password123");
        mockMvc.perform(get("/api/me/shade-code-scheme")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value(""))
                .andExpect(jsonPath("$.infix").value(""))
                .andExpect(jsonPath("$.suffix").value(""));
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
