package com.gridstore.huevista.admin;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.dto.RegisterRequest;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.model.AuthProvider;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AdminControllerIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        // Create an admin user directly via repository (bypasses registration which sets RETAILER role)
        userRepository.save(User.builder()
                .name("Admin User")
                .email("admin@huevista.com")
                .password(passwordEncoder.encode("admin-pass"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN)
                .emailVerified(true)
                .build());

        // Login to get token
        String loginPayload = """
                {"email":"admin@huevista.com","password":"admin-pass"}
                """;
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        adminToken = resp.getAccessToken();
    }

    @Test
    void list_users_requires_admin() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_can_list_users() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void admin_can_get_stats() throws Exception {
        mockMvc.perform(get("/api/admin/stats")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").isNumber())
                .andExpect(jsonPath("$.totalProjects").isNumber())
                .andExpect(jsonPath("$.activeSubscriptions").isNumber());
    }

    @Test
    void admin_can_get_revenue_stats() throws Exception {
        mockMvc.perform(get("/api/admin/stats/revenue")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEstimatedMonthlyRevenueInRupees").isNumber());
    }

    @Test
    void admin_can_get_ai_usage_stats() throws Exception {
        mockMvc.perform(get("/api/admin/stats/ai-usage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAiGenerationsUsedThisCycle").isNumber());
    }

    @Test
    void admin_can_get_recent_users() throws Exception {
        mockMvc.perform(get("/api/admin/users/recent")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void non_admin_user_cannot_access_admin_endpoints() throws Exception {
        // Register a normal user
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Normal User");
        reg.setEmail("normal@example.com");
        reg.setPassword("password123");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        String normalToken = resp.getAccessToken();

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + normalToken))
                .andExpect(status().isForbidden());
    }
}
