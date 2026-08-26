package com.gridstore.huevista.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.PhoneLoginCodeRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.notification.SmsSender;
import com.razorpay.RazorpayClient;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing in with a code this server texts itself — the MSG91 path.
 *
 * <p>The SMS sender is mocked, which is the whole point of the seam: MSG91 is a pipe, so
 * everything that decides whether somebody gets a session happens here, on our side of
 * it, and can be tested without a provider account or a DLT registration.
 *
 * <p>Firebase is left unconfigured in this class so {@code /phone/methods} reports SMS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties",
        properties = {
                "app.firebase.project-id=",
                "app.sms.otp.max-per-number-per-day=3",
        })
class PhoneOtpSignInIntegrationTest {

    @MockitoBean RazorpayClient razorpayClient;

    /** Stands in for MSG91. Captures the code so the test can type it back. */
    @MockitoBean SmsSender smsSender;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PhoneLoginCodeRepository codeRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final AtomicReference<String> lastCode = new AtomicReference<>();

    @BeforeEach
    void armSender() {
        when(smsSender.deliversForReal()).thenReturn(true);
        doAnswer(inv -> {
            lastCode.set(inv.getArgument(1));
            return null;
        }).when(smsSender).sendOtp(anyString(), anyString(), anyInt());
    }

    // ---- the happy path ----------------------------------------------------

    @Test
    void a_texted_code_opens_a_passwordless_customer_account() throws Exception {
        String phone = "+919700000001";

        send(phone, "Asha Patel")
                .andExpect(status().isOk())
                // Masked, not echoed in full: a public endpoint should not repeat a
                // mobile number to whoever asked.
                .andExpect(jsonPath("$.destination").value("**********001"))
                .andExpect(jsonPath("$.expiresInSeconds").value(600));

        String body = verifyCode(phone, lastCode.get())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("Asha Patel"))
                .andExpect(jsonPath("$.user.provider").value("PHONE"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.email").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        User created = userRepository.findById(userIdOf(body)).orElseThrow();
        assertThat(created.getPhoneNumber()).isEqualTo(phone);
        assertThat(created.isPhoneVerified()).isTrue();
        assertThat(created.getPassword()).isNull();
    }

    @Test
    void the_same_number_comes_back_to_the_same_account() throws Exception {
        String phone = "+919700000002";
        send(phone, "Ravi").andExpect(status().isOk());
        String first = verifyCode(phone, lastCode.get()).andReturn().getResponse().getContentAsString();

        expireCooldown(phone);
        send(phone, "Someone Else").andExpect(status().isOk());
        String second = verifyCode(phone, lastCode.get()).andReturn().getResponse().getContentAsString();

        assertThat(userIdOf(second)).isEqualTo(userIdOf(first));
        assertThat(objectMapper.readTree(second).at("/user/name").asText()).isEqualTo("Ravi");
    }

    @Test
    void a_number_verified_on_an_email_account_signs_into_that_account() throws Exception {
        // The rules that decide this live in PhoneAccountService, shared with the
        // Firebase path. This proves the SMS path really does go through them.
        String phone = "+919700000003";
        User existing = userRepository.save(User.builder()
                .name("Meera Shah").email("meera-otp@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.CUSTOMER)
                .phoneNumber(phone).phoneVerified(true).build());

        send(phone, "Ignored").andExpect(status().isOk());
        String body = verifyCode(phone, lastCode.get())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Meera Shah"))
                .andReturn().getResponse().getContentAsString();

        assertThat(userIdOf(body)).isEqualTo(existing.getId());
    }

    @Test
    void an_admin_account_is_refused_and_the_code_is_still_spent() throws Exception {
        String phone = "+919700000004";
        userRepository.save(User.builder()
                .name("Platform Admin").email("admin-otp@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .phoneNumber(phone).phoneVerified(true).build());

        send(phone, null).andExpect(status().isOk());
        verifyCode(phone, lastCode.get())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("email address and password")));
    }

    // ---- the code itself ---------------------------------------------------

    @Test
    void the_code_is_never_stored_in_the_clear() throws Exception {
        String phone = "+919700000005";
        send(phone, null).andExpect(status().isOk());

        List<com.gridstore.huevista.auth.model.PhoneLoginCode> stored =
                codeRepository.findByPhoneNumberAndConsumedFalse(phone);
        assertThat(stored).hasSize(1);
        // A database read must not hand over a sign-in.
        assertThat(stored.get(0).getCodeHash()).isNotEqualTo(lastCode.get());
        assertThat(passwordEncoder.matches(lastCode.get(), stored.get(0).getCodeHash())).isTrue();
    }

    @Test
    void a_wrong_code_is_refused_and_counts_against_the_attempt_limit() throws Exception {
        String phone = "+919700000006";
        send(phone, null).andExpect(status().isOk());

        for (int i = 0; i < 5; i++) {
            verifyCode(phone, "000000").andExpect(status().isBadRequest());
        }
        // Exhausted — even the RIGHT code is now refused, so guessing cannot be resumed.
        verifyCode(phone, lastCode.get())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Ask for a new code")));
    }

    @Test
    void a_code_cannot_be_used_twice() throws Exception {
        String phone = "+919700000007";
        send(phone, null).andExpect(status().isOk());
        String code = lastCode.get();

        verifyCode(phone, code).andExpect(status().isOk());
        verifyCode(phone, code).andExpect(status().isBadRequest());
    }

    @Test
    void asking_for_a_new_code_kills_the_old_one() throws Exception {
        // Otherwise every code sent today stays live for its full ten minutes and an
        // attacker gets one set of guesses per code instead of one in total.
        String phone = "+919700000008";
        send(phone, null).andExpect(status().isOk());
        String superseded = lastCode.get();

        expireCooldown(phone);
        send(phone, null).andExpect(status().isOk());

        verifyCode(phone, superseded).andExpect(status().isBadRequest());
        verifyCode(phone, lastCode.get()).andExpect(status().isOk());
    }

    @Test
    void an_expired_code_is_refused() throws Exception {
        String phone = "+919700000009";
        send(phone, null).andExpect(status().isOk());
        codeRepository.findByPhoneNumberAndConsumedFalse(phone).forEach(c -> {
            c.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            codeRepository.save(c);
        });

        verifyCode(phone, lastCode.get())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("expired")));
    }

    @Test
    void verifying_without_asking_first_is_refused() throws Exception {
        verifyCode("+919700000010", "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Ask for a code first")));
    }

    // ---- the limits that bound the bill ------------------------------------

    @Test
    void a_second_code_inside_the_cooldown_sends_no_sms() throws Exception {
        String phone = "+919700000011";
        send(phone, null).andExpect(status().isOk());

        send(phone, null).andExpect(status().isTooManyRequests());
        // The point is not the status code — it is that no message was paid for.
        verify(smsSender, org.mockito.Mockito.times(1)).sendOtp(anyString(), anyString(), anyInt());
    }

    @Test
    void a_number_cannot_be_texted_past_its_daily_cap() throws Exception {
        // The cooldown alone only paces an attacker; it does not stop them texting a
        // stranger all night at our expense. Capped at 3 for this test.
        String phone = "+919700000012";
        for (int i = 0; i < 3; i++) {
            send(phone, null).andExpect(status().isOk());
            expireCooldown(phone);
        }
        send(phone, null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("too many codes today")));

        verify(smsSender, org.mockito.Mockito.times(3)).sendOtp(anyString(), anyString(), anyInt());
    }

    @Test
    void a_malformed_number_costs_nothing() throws Exception {
        send("not-a-number", null).andExpect(status().isBadRequest());
        verify(smsSender, never()).sendOtp(anyString(), anyString(), anyInt());
    }

    @Test
    void the_same_number_written_differently_is_one_number() throws Exception {
        // Otherwise the cooldown and the daily cap are both trivially bypassed by adding
        // a space, and one handset can be texted as many times as there are spellings.
        String phone = "+919700000013";
        send(phone, null).andExpect(status().isOk());
        send("+91 97000 00013", null).andExpect(status().isTooManyRequests());
        verify(smsSender, org.mockito.Mockito.times(1)).sendOtp(anyString(), anyString(), anyInt());
    }

    // ---- what the server admits to -----------------------------------------

    @Test
    void the_methods_endpoint_reports_SMS_when_only_an_sms_provider_is_configured() throws Exception {
        mockMvc.perform(get("/api/auth/phone/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("SMS"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void the_send_step_answers_the_same_whether_or_not_the_number_has_an_account() throws Exception {
        // A public endpoint that answered differently would be a free tool for asking
        // whether a given person is a HueVista customer.
        userRepository.save(User.builder()
                .name("Known").email("known-otp@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.CUSTOMER)
                .phoneNumber("+919700000014").phoneVerified(true).build());

        String known = send("+919700000014", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String unknown = send("+919700000015", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(known).at("/expiresInSeconds"))
                .isEqualTo(objectMapper.readTree(unknown).at("/expiresInSeconds"));
        assertThat(objectMapper.readTree(known).at("/cooldownSeconds"))
                .isEqualTo(objectMapper.readTree(unknown).at("/cooldownSeconds"));
    }

    // ---- helpers -----------------------------------------------------------

    private ResultActions send(String phone, String name) throws Exception {
        return mockMvc.perform(post("/api/auth/phone/otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        name == null ? Map.of("phone", phone) : Map.of("phone", phone, "name", name))));
    }

    private ResultActions verifyCode(String phone, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/phone/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))));
    }

    /** Age the newest code past the resend cooldown without sleeping for it. */
    private void expireCooldown(String phone) {
        codeRepository.findTopByPhoneNumberOrderByCreatedAtDesc(phone).ifPresent(c -> {
            c.setCreatedAt(LocalDateTime.now().minusMinutes(2));
            codeRepository.save(c);
        });
    }

    private String userIdOf(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).at("/user/id").asText();
    }
}
