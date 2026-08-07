package com.gridstore.huevista.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.notification.EmailSender;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account-takeover tripwire: changing a password has to tell the address on the
 * account, whichever route changed it.
 *
 * <p>Both flows already revoked every session silently, which is precisely the sequence
 * an attacker produces — so the absence of the mail was the difference between a user
 * who can act and one who finds out weeks later.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class AccountSecurityEmailIntegrationTest {

    @MockitoBean
    RazorpayClient razorpayClient;

    /** Mocked so the test can read back what would have been sent. */
    @MockitoBean
    EmailSender emailSender;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final String EMAIL = "asha@example.com";

    @Test
    void changing_a_password_warns_the_account_it_happened() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword456\"}"))
                .andExpect(status().isOk());

        String body = lastEmailTo(EMAIL);
        assertThat(body).contains("password was changed");
        // The sign-out is the part a victim would otherwise notice with no explanation.
        assertThat(body).contains("signed out everywhere");
        // Someone who did not do this needs a way back in, not just the bad news.
        assertThat(body).contains("/sign-in/forgot");
    }

    @Test
    void resetting_a_password_by_code_warns_the_account_too() throws Exception {
        registerAndGetToken();

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk());
        String code = codeFromEmail();

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"code\":\"" + code
                                + "\",\"newPassword\":\"newpassword456\"}"))
                .andExpect(status().isOk());

        String body = lastEmailTo(EMAIL);
        assertThat(body).contains("password was reset");
        // The reset came by email, so the mailbox is what an impostor would have needed.
        assertThat(body).contains("this mailbox");
        assertThat(body).contains("/sign-in/forgot");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String registerAndGetToken() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha Rao\",\"email\":\"" + EMAIL
                                + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return node.get("accessToken").asText();
    }

    /** The body of the most recent mail "sent" to {@code to}. */
    private String lastEmailTo(String to) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(to), anyString(), body.capture());
        return body.getAllValues().get(body.getAllValues().size() - 1);
    }

    /** Pull the 6-digit reset code out of the mail the service "sent". */
    private String codeFromEmail() {
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(lastEmailTo(EMAIL));
        if (!m.find()) throw new AssertionError("No reset code was emailed to " + EMAIL);
        return m.group(1);
    }
}
