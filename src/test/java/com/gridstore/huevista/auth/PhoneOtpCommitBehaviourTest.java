package com.gridstore.huevista.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.PhoneLoginCodeRepository;
import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.notification.SmsSender;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.AfterEach;
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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SMS sign-in cases whose behaviour only appears when a transaction actually COMMITS.
 *
 * <p>Deliberately NOT {@code @Transactional}. A test transaction is rolled back when the
 * test ends and never commits, which hides every conflict that surfaces at commit time —
 * and one was hiding here. The admin refusal used to answer 500 rather than 403 in
 * production while a {@code @Transactional} test of the very same request passed, because
 * the nested transaction marked itself rollback-only and the outer one then tried to
 * commit anyway. Only a real commit shows that.
 *
 * <p>The cost is having to clean up after itself, which is what {@link #tidy} is for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.properties",
        properties = "app.firebase.project-id=")
class PhoneOtpCommitBehaviourTest {

    private static final String ADMIN_PHONE = "+919555000001";
    private static final String CUSTOMER_PHONE = "+919555000002";

    @MockitoBean RazorpayClient razorpayClient;
    @MockitoBean SmsSender smsSender;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PhoneLoginCodeRepository codeRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwordEncoder;

    private final AtomicReference<String> lastCode = new AtomicReference<>();

    @BeforeEach
    void armSender() {
        when(smsSender.deliversForReal()).thenReturn(true);
        doAnswer(inv -> {
            lastCode.set(inv.getArgument(1));
            return null;
        }).when(smsSender).sendOtp(anyString(), anyString(), anyInt());
        tidy();
    }

    /**
     * Housekeeping needs a transaction; the TESTS must not have one, which is this
     * class's entire reason for existing. So the cleanup opens its own rather than the
     * class borrowing {@code @Transactional} and losing the commit behaviour it is here
     * to observe.
     */
    @AfterEach
    void tidy() {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> removeMyRows());
    }

    private void removeMyRows() {
        java.util.Set<String> mine = java.util.Set.of(ADMIN_PHONE, CUSTOMER_PHONE);
        // Not findActiveForUpdate: that one takes a pessimistic lock and there is no
        // transaction here — which is the whole point of this class.
        codeRepository.deleteAll(codeRepository.findAll().stream()
                .filter(c -> mine.contains(c.getPhoneNumber()))
                .toList());
        java.util.List<User> users = userRepository.findAll().stream()
                .filter(u -> u.getPhoneNumber() != null && mine.contains(u.getPhoneNumber()))
                .toList();
        // A successful sign-in leaves a refresh token pointing at the row, and the
        // foreign key means it has to go first.
        users.forEach(refreshTokenRepository::deleteByUser);
        userRepository.deleteAll(users);
    }

    @Test
    void an_admin_is_refused_with_403_and_not_a_500() throws Exception {
        userRepository.save(User.builder()
                .name("Platform Admin").email("admin-commit@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .phoneNumber(ADMIN_PHONE).phoneVerified(true).build());

        send(ADMIN_PHONE).andExpect(status().isOk());
        verify(ADMIN_PHONE, lastCode.get())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("email address and password")));

    }

    @Test
    void a_wrong_code_really_persists_its_attempt() throws Exception {
        // The other half of the same trade-off: this one MUST commit, or the attempt
        // limit counts nothing and the code can be guessed indefinitely.
        send(CUSTOMER_PHONE).andExpect(status().isOk());

        verify(CUSTOMER_PHONE, "000000").andExpect(status().isBadRequest());

        assertThat(codeRepository.findTopByPhoneNumberOrderByCreatedAtDesc(CUSTOMER_PHONE)
                .orElseThrow().getAttempts())
                .as("the attempt must survive the failed request")
                .isEqualTo(1);
    }

    @Test
    void a_good_code_really_opens_an_account() throws Exception {
        send(CUSTOMER_PHONE).andExpect(status().isOk());
        verify(CUSTOMER_PHONE, lastCode.get()).andExpect(status().isOk());

        assertThat(userRepository
                .findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc(CUSTOMER_PHONE))
                .hasSize(1);
    }

    private org.springframework.test.web.servlet.ResultActions send(String phone) throws Exception {
        return mockMvc.perform(post("/api/auth/phone/otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phone", phone))));
    }

    private org.springframework.test.web.servlet.ResultActions verify(String phone, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/phone/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))));
    }
}
