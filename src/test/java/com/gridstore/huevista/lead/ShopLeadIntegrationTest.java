package com.gridstore.huevista.lead;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.lead.model.ShopLead;
import com.gridstore.huevista.lead.repository.ShopLeadRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public "request a shop account" funnel: anyone can submit a lead, only an
 * admin can read the queue and work it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ShopLeadIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShopLeadRepository leadRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void anonymous_visitor_can_submit_a_lead() throws Exception {
        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Priya Mehta","email":"priya@mehtapaints.in","phone":"+919822104476",
                                 "shopName":"Mehta Paint House","city":"Pune","state":"Maharashtra",
                                 "tier":"pro","notes":"Busy weekend counter."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shopName").value("Mehta Paint House"))
                .andExpect(jsonPath("$.status").value("NEW"));

        assertThat(leadRepository.findAll()).hasSize(1);
        ShopLead saved = leadRepository.findAll().get(0);
        assertThat(saved.getEmail()).isEqualTo("priya@mehtapaints.in");
        assertThat(saved.getStatus()).isEqualTo(ShopLead.Status.NEW);
    }

    @Test
    void submission_requires_name_email_and_shop() throws Exception {
        mockMvc.perform(post("/api/leads/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.shopName").exists());
    }

    @Test
    void lead_queue_is_admin_only_and_status_can_be_worked() throws Exception {
        ShopLead lead = leadRepository.save(ShopLead.builder()
                .name("Owner").email("owner@example.in").shopName("Shree Colours").build());

        // Anonymous → 401; non-admin → 403.
        mockMvc.perform(get("/api/admin/leads")).andExpect(status().isUnauthorized());

        userRepository.save(User.builder().name("Cust").email("cust@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.CUSTOMER).build());
        String custToken = login("cust@example.com");
        mockMvc.perform(get("/api/admin/leads").header("Authorization", "Bearer " + custToken))
                .andExpect(status().isForbidden());

        // Admin sees the queue and can mark the lead contacted.
        userRepository.save(User.builder().name("Root").email("root@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).emailVerified(true)
                .role(UserRole.ADMIN).build());
        String adminToken = login("root@example.com");

        mockMvc.perform(get("/api/admin/leads").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shopName").value("Shree Colours"));

        mockMvc.perform(patch("/api/admin/leads/" + lead.getId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"contacted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTACTED"));

        mockMvc.perform(patch("/api/admin/leads/" + lead.getId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"nonsense\"}"))
                .andExpect(status().isBadRequest());
    }

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getAccessToken();
    }
}
