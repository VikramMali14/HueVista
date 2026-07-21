package com.gridstore.huevista.hierarchy;

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
import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.repository.PainterRetailerLinkRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the account hierarchy: admin → distributor → retailer
 * → painter provisioning, the auto-links between levels, and the role-scoped
 * network report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class HierarchyFlowIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DistributorRetailerLinkRepository distributorLinkRepository;
    @Autowired PainterRetailerLinkRepository painterLinkRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String tokenFor(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(login.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }

    private User seedAdmin() {
        return userRepository.save(User.builder()
                .name("Root Admin").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
    }

    @Test
    void full_chain_admin_distributor_retailer_painter() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");

        // Admin creates a distributor.
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Arun","email":"dist@example.com","password":"password123",
                                 "companyName":"Shetty Trade","city":"Hubli","state":"Karnataka"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DISTRIBUTOR"));

        User distributor = userRepository.findByEmail("dist@example.com").orElseThrow();
        assertThat(distributor.getCreatedById()).isNotNull();
        assertThat(organizationRepository.findByOwnerIdAndType(distributor.getId(), OrgType.DISTRIBUTOR)).hasSize(1);

        // Distributor creates a retailer (auto-linked to the distributor).
        String distToken = tokenFor("dist@example.com", "password123");
        mockMvc.perform(post("/api/hierarchy/retailers")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya","email":"shop@example.com","password":"password123",
                                 "shopName":"Mehta Paints","city":"Pune","state":"Maharashtra","tier":"pro"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("RETAILER"));

        User retailer = userRepository.findByEmail("shop@example.com").orElseThrow();
        assertThat(retailer.getCreatedById()).isEqualTo(distributor.getId());
        Organization distOrg = organizationRepository.findByOwnerIdAndType(distributor.getId(), OrgType.DISTRIBUTOR).get(0);
        Organization retailerOrg = organizationRepository.findByOwnerIdAndType(retailer.getId(), OrgType.RETAILER).get(0);
        assertThat(distributorLinkRepository.existsByDistributorIdAndRetailerId(distOrg.getId(), retailerOrg.getId())).isTrue();

        // Retailer creates a painter (auto-linked ACTIVE to the shop).
        String shopToken = tokenFor("shop@example.com", "password123");
        mockMvc.perform(post("/api/hierarchy/painters")
                        .header("Authorization", "Bearer " + shopToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Santosh","email":"painter@example.com","password":"password123",
                                 "phone":"+919156883402"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PAINTER"));

        User painter = userRepository.findByEmail("painter@example.com").orElseThrow();
        assertThat(painter.getCreatedById()).isEqualTo(retailer.getId());
        assertThat(painterLinkRepository.findByRetailerIdAndStatus(retailerOrg.getId(), PainterLinkStatus.ACTIVE)).hasSize(1);

        // Admin network report sees the whole chain.
        MvcResult adminReport = mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("ADMIN"))
                .andReturn();
        JsonNode admin = objectMapper.readTree(adminReport.getResponse().getContentAsString());
        assertThat(admin.get("totals").get("distributors").asLong()).isEqualTo(1);
        assertThat(admin.get("totals").get("retailers").asLong()).isEqualTo(1);
        assertThat(admin.get("totals").get("painters").asLong()).isEqualTo(1);
        // Distributor root → retailer child → painter grandchild.
        JsonNode distNode = admin.get("roots").get(0);
        assertThat(distNode.get("role").asText()).isEqualTo("DISTRIBUTOR");
        assertThat(distNode.get("retailerCount").asLong()).isEqualTo(1);
        assertThat(distNode.get("painterCount").asLong()).isEqualTo(1);
        JsonNode retailerNode = distNode.get("children").get(0);
        assertThat(retailerNode.get("role").asText()).isEqualTo("RETAILER");
        assertThat(retailerNode.get("children").get(0).get("role").asText()).isEqualTo("PAINTER");

        // Distributor report is scoped to their own subtree.
        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + distToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("DISTRIBUTOR"))
                .andExpect(jsonPath("$.totals.retailers").value(1))
                .andExpect(jsonPath("$.totals.painters").value(1))
                .andExpect(jsonPath("$.roots[0].children[0].role").value("RETAILER"));

        // Retailer report shows their painter roster.
        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + shopToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("RETAILER"))
                .andExpect(jsonPath("$.totals.painters").value(1))
                .andExpect(jsonPath("$.roots[0].children[0].role").value("PAINTER"));
    }

    @Test
    void admin_created_retailer_is_a_direct_root_with_no_distributor() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");

        mockMvc.perform(post("/api/admin/retailers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Direct Owner","email":"direct@example.com","password":"password123",
                                 "shopName":"Direct Paints","tier":"starter"}"""))
                .andExpect(status().isCreated());

        User retailer = userRepository.findByEmail("direct@example.com").orElseThrow();
        assertThat(retailer.getCreatedById()).isNotNull(); // the admin
        assertThat(retailer.getRole()).isEqualTo(UserRole.RETAILER);

        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roots[0].role").value("RETAILER"))
                .andExpect(jsonPath("$.totals.distributors").value(0))
                .andExpect(jsonPath("$.totals.retailers").value(1));
    }

    @Test
    void distributor_cannot_create_painters() throws Exception {
        seedAdmin();
        String adminToken = tokenFor("root@example.com", "password123");
        mockMvc.perform(post("/api/admin/distributors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Arun","email":"dist2@example.com","password":"password123",
                                 "companyName":"Shetty Trade"}"""))
                .andExpect(status().isCreated());
        String distToken = tokenFor("dist2@example.com", "password123");

        // /painters is RETAILER-only — a distributor is forbidden.
        mockMvc.perform(post("/api/hierarchy/painters")
                        .header("Authorization", "Bearer " + distToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"x@example.com","password":"password123"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void non_privileged_user_cannot_read_network_report() throws Exception {
        // A plain customer (public signup) has no downline and no access.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cust","email":"cust@example.com","password":"password123"}"""))
                .andExpect(status().isCreated());
        String custToken = tokenFor("cust@example.com", "password123");

        mockMvc.perform(get("/api/hierarchy/network")
                        .header("Authorization", "Bearer " + custToken))
                .andExpect(status().isForbidden());
    }
}
