package com.gridstore.huevista.auth;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.OAuthExchangeCodeRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.AuthService;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one-time OAuth exchange: the Google callback now lands with a short-lived
 * single-use code instead of real tokens (which used to sit in the URL fragment
 * for extensions and history to read). These tests pin the contract: a live code
 * trades for tokens exactly once; replays, expiry and garbage all get 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class OAuthExchangeIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired OAuthExchangeCodeRepository codeRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .name("Google User").email("google-user@example.com")
                .provider(AuthProvider.GOOGLE).providerId("google-sub-1")
                .emailVerified(true)
                .build());
    }

    @Test
    void code_exchanges_for_tokens_exactly_once() throws Exception {
        String code = authService.createOAuthExchangeCode(user);

        mockMvc.perform(post("/api/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("google-user@example.com"));

        // Replay — the whole point: a code scraped from the URL later is worthless.
        mockMvc.perform(post("/api/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expired_code_is_rejected() throws Exception {
        String code = authService.createOAuthExchangeCode(user);
        codeRepository.findAll().forEach(c -> {
            c.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            codeRepository.save(c);
        });

        mockMvc.perform(post("/api/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknown_code_is_rejected() throws Exception {
        mockMvc.perform(post("/api/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"not-a-real-code\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void minting_a_new_code_invalidates_the_previous_one() throws Exception {
        String first = authService.createOAuthExchangeCode(user);
        String second = authService.createOAuthExchangeCode(user);

        mockMvc.perform(post("/api/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + first + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + second + "\"}"))
                .andExpect(status().isOk());
    }
}
