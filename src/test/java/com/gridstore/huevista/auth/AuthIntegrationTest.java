package com.gridstore.huevista.auth;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.dto.LoginRequest;
import com.gridstore.huevista.auth.dto.RefreshTokenRequest;
import com.gridstore.huevista.auth.dto.RegisterRequest;
import com.gridstore.huevista.auth.model.RefreshToken;
import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import com.gridstore.huevista.auth.util.TokenHasher;
import com.razorpay.RazorpayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AuthIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @Test
    void register_login_refresh_logout_flow() throws Exception {
        // 1. Register
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Test User");
        reg.setEmail("test@example.com");
        reg.setPassword("password123");

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        AuthResponse regResp = objectMapper.readValue(
                regResult.getResponse().getContentAsString(), AuthResponse.class);
        String refreshToken = regResp.getRefreshToken();

        // 2. Duplicate email → 409
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isConflict());

        // 3. Login
        LoginRequest login = new LoginRequest();
        login.setEmail("test@example.com");
        login.setPassword("password123");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        AuthResponse loginResp = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class);
        String accessToken = loginResp.getAccessToken();

        // 4. Refresh
        RefreshTokenRequest refreshReq = new RefreshTokenRequest();
        refreshReq.setRefreshToken(refreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 5. /me with valid token
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNotEmpty());

        // 6. Logout
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 7. /me after logout → still works (JWT is stateless until expiry)
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_tokens_are_stored_hashed_not_plaintext() throws Exception {
        String rawToken = registerAndGetRefreshToken("hash-check@example.com");

        // The raw token must not exist anywhere in the table; only its SHA-256 may.
        assertThat(refreshTokenRepository.findByToken(rawToken)).isEmpty();
        assertThat(refreshTokenRepository.findByToken(TokenHasher.sha256Hex(rawToken))).isPresent();
    }

    @Test
    void parallel_refresh_reuse_within_grace_window_succeeds() throws Exception {
        String rawToken = registerAndGetRefreshToken("race@example.com");

        // First refresh consumes the token (the "winning" request).
        postRefresh(rawToken).andExpect(status().isOk());

        // A second request replaying the SAME token (the racing tab that lost) must
        // still get a fresh pair within the grace window — not a session-clearing 401.
        postRefresh(rawToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void refresh_reuse_after_grace_window_revokes_all_sessions() throws Exception {
        String rawToken = registerAndGetRefreshToken("theft@example.com");

        // First rotation succeeds and yields a NEW token.
        MvcResult first = postRefresh(rawToken).andExpect(status().isOk()).andReturn();
        String newToken = objectMapper.readValue(
                first.getResponse().getContentAsString(), AuthResponse.class).getRefreshToken();

        // Backdate the consumed row so the replay falls outside the grace window.
        RefreshToken consumed = refreshTokenRepository
                .findByToken(TokenHasher.sha256Hex(rawToken)).orElseThrow();
        consumed.setUsedAt(java.time.Instant.now().minusSeconds(3600));
        refreshTokenRepository.saveAndFlush(consumed);

        // Stale replay → 401, and every live session for the user is revoked.
        postRefresh(rawToken).andExpect(status().isUnauthorized());
        postRefresh(newToken).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_with_unknown_token_returns_401() throws Exception {
        postRefresh("never-issued-token").andExpect(status().isUnauthorized());
    }

    private String registerAndGetRefreshToken(String email) throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Refresh Tester");
        reg.setEmail(email);
        reg.setPassword("password123");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class).getRefreshToken();
    }

    private org.springframework.test.web.servlet.ResultActions postRefresh(String rawToken) throws Exception {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(rawToken);
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));
    }

    @Test
    void login_with_wrong_password_returns_401() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Test User 2");
        reg.setEmail("test2@example.com");
        reg.setPassword("correct-password1");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        LoginRequest badLogin = new LoginRequest();
        badLogin.setEmail("test2@example.com");
        badLogin.setPassword("wrong-password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticated_request_returns_401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_with_invalid_email_returns_400() throws Exception {
        RegisterRequest bad = new RegisterRequest();
        bad.setName("Test");
        bad.setEmail("not-an-email");
        bad.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }
}
