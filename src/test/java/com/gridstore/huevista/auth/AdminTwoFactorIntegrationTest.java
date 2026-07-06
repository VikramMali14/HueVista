package com.gridstore.huevista.auth;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.notification.EmailSender;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin login 2FA: when mail delivery is configured, an ADMIN's correct password
 * yields twoFactorRequired (no tokens) plus an emailed 6-digit code; the session
 * is only issued by /login/otp with password AND code together. Non-admin logins
 * and mail-less deployments are untouched (the step-up must never lock anyone out).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AdminTwoFactorIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    // Mocked so the test controls isDeliveryEnabled() and can read the emailed code.
    @MockitoBean
    EmailSender emailSender;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final Pattern CODE_IN_MAIL = Pattern.compile("code is: (\\d{6})");

    @BeforeEach
    void setUp() {
        userRepository.save(User.builder()
                .name("Admin").email("admin2fa@huevista.com")
                .password(passwordEncoder.encode("admin-pass"))
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .emailVerified(true).build());
        userRepository.save(User.builder()
                .name("Shop").email("shop2fa@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.RETAILER)
                .emailVerified(true).build());
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String otpBody(String email, String password, String code) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"code\":\"" + code + "\"}";
    }

    private String emailedCode() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(anyString(), anyString(), body.capture());
        Matcher m = CODE_IN_MAIL.matcher(body.getValue());
        assertThat(m.find()).as("6-digit code present in the email body").isTrue();
        return m.group(1);
    }

    @Test
    void admin_login_requires_the_emailed_code_when_mail_is_on() throws Exception {
        when(emailSender.isDeliveryEnabled()).thenReturn(true);

        // Step 1: correct password → challenge, no tokens.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin2fa@huevista.com", "admin-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        String code = emailedCode();

        // A wrong code burns an attempt and is rejected (400, not 401).
        mockMvc.perform(post("/api/auth/login/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody("admin2fa@huevista.com", "admin-pass", "000000")))
                .andExpect(status().isBadRequest());

        // The right code + password issues the session.
        mockMvc.perform(post("/api/auth/login/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody("admin2fa@huevista.com", "admin-pass", code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));

        // Single use: the same code cannot start a second session.
        mockMvc.perform(post("/api/auth/login/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody("admin2fa@huevista.com", "admin-pass", code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otp_step_requires_the_password_too() throws Exception {
        when(emailSender.isDeliveryEnabled()).thenReturn(true);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin2fa@huevista.com", "admin-pass")))
                .andExpect(status().isOk());
        String code = emailedCode();

        // An intercepted code without the password is worthless.
        mockMvc.perform(post("/api/auth/login/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody("admin2fa@huevista.com", "wrong-password", code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void non_admins_and_mailless_deployments_log_in_directly() throws Exception {
        when(emailSender.isDeliveryEnabled()).thenReturn(true);

        // A retailer is never challenged.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("shop2fa@example.com", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.twoFactorRequired").doesNotExist());

        // No SMTP configured → the admin still gets in (2FA must not lock out).
        when(emailSender.isDeliveryEnabled()).thenReturn(false);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin2fa@huevista.com", "admin-pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
