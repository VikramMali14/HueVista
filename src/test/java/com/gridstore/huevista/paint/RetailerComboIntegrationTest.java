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
import com.gridstore.huevista.auth.model.UserRole;
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
 * Retailer-curated shade combinations ("shop picks"): portal CRUD by the shop
 * owner, and studio reads via /api/me/retailer-combos for every principal type
 * that visualises under the shop — retailer staff, entitled customers, guests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class RetailerComboIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean com.gridstore.huevista.project.queue.SegmentationJobQueue segmentationJobQueue;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository orgRepository;
    @Autowired OrgMembershipRepository membershipRepository;
    @Autowired CustomerAccessCodeRepository codeRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String CODE = "COMBOAB1";

    private String orgId;
    private String retailerToken;

    private static final String COMBO_JSON = """
            {"name":"Warm evening","scope":"INTERIOR","shades":[
              {"code":"AP-2118","name":"Terracotta","hex":"#A47148"},
              {"code":"AP-2215","name":"Champagne","hex":"#dac1a3"},
              {"code":"AP-N108","name":"Porcelain","hex":"#e8e6df"}]}""";

    @BeforeEach
    void setUp() throws Exception {
        User retailer = userRepository.save(User.builder()
                .name("Shop Owner").email("shop@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());

        Organization org = orgRepository.save(Organization.builder()
                .name("Sharda Paints").slug("sharda-paints-combotest")
                .type(OrgType.RETAILER).owner(retailer).build());
        orgId = org.getId();

        membershipRepository.save(OrgMembership.builder()
                .user(retailer).organization(org).role(OrgMemberRole.OWNER).build());

        codeRepository.save(CustomerAccessCode.builder()
                .organization(org).code(CODE).validDays(7)
                .expiresAt(LocalDateTime.now().plusDays(7)).build());

        retailerToken = login("shop@example.com", "password123");
    }

    @Test
    void owner_creates_lists_and_deletes_combos() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMBO_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Warm evening"))
                .andExpect(jsonPath("$.scope").value("INTERIOR"))
                .andExpect(jsonPath("$.shades.length()").value(3))
                // Hex is normalised to lowercase on the way in.
                .andExpect(jsonPath("$.shades[0].hex").value("#a47148"))
                .andReturn();
        String comboId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(delete("/api/organizations/" + orgId + "/combos/" + comboId)
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void combo_requires_exactly_three_shades_and_a_valid_hex() throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Two only","scope":"INTERIOR","shades":[
                                  {"code":"A","name":"A","hex":"#aaaaaa"},
                                  {"code":"B","name":"B","hex":"#bbbbbb"}]}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad hex","scope":"EXTERIOR","shades":[
                                  {"code":"A","name":"A","hex":"red"},
                                  {"code":"B","name":"B","hex":"#bbbbbb"},
                                  {"code":"C","name":"C","hex":"#cccccc"}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_stranger_cannot_manage_or_read_another_shops_combos() throws Exception {
        userRepository.save(User.builder().name("Other").email("other@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String otherToken = login("other@example.com", "password123");

        mockMvc.perform(post("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMBO_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void retailer_sees_their_own_combos_in_the_studio_feed() throws Exception {
        createCombo();
        mockMvc.perform(get("/api/me/retailer-combos")
                        .header("Authorization", "Bearer " + retailerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].organizationName").value("Sharda Paints"));
    }

    @Test
    void entitled_customer_sees_the_shops_combos_and_a_stranger_sees_none() throws Exception {
        createCombo();

        // A CUSTOMER who redeemed the shop's access code gets the combos…
        userRepository.save(User.builder().name("Walk In").email("walkin@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());
        String customerToken = login("walkin@example.com", "password123");
        mockMvc.perform(post("/api/access-codes/redeem")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/me/retailer-combos")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Warm evening"));

        // …while an unrelated user sees an empty list, not an error.
        userRepository.save(User.builder().name("Nobody").email("nobody@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true).build());
        String strangerToken = login("nobody@example.com", "password123");
        mockMvc.perform(get("/api/me/retailer-combos")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void guest_on_a_shop_code_sees_the_shops_combos() throws Exception {
        createCombo();

        MvcResult r = mockMvc.perform(post("/api/access-codes/redeem-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String guestToken = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("guestToken").asText();

        mockMvc.perform(get("/api/me/retailer-combos")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].shades[0].code").value("AP-2118"));
    }

    private void createCombo() throws Exception {
        mockMvc.perform(post("/api/organizations/" + orgId + "/combos")
                        .header("Authorization", "Bearer " + retailerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMBO_JSON))
                .andExpect(status().isCreated());
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
