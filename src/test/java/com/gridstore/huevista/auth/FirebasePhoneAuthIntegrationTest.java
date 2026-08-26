package com.gridstore.huevista.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.razorpay.RazorpayClient;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing in with a mobile number, end to end: Firebase ID token in, HueVista session out.
 *
 * <p>The tokens are real ones — signed with a keypair {@link FirebaseCerts} serves from a
 * local stand-in for Google's certificate endpoint — so the whole verification path runs
 * exactly as it does in production. What is exercised here is what happens AFTER the
 * token checks out: which account the caller lands on, and which callers are turned away.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class FirebasePhoneAuthIntegrationTest {

    private static final String PROJECT = "huevista-test";
    private static final FirebaseCerts CERTS = certs();

    @MockitoBean
    RazorpayClient razorpayClient;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void firebase(DynamicPropertyRegistry registry) {
        registry.add("app.firebase.project-id", () -> PROJECT);
        registry.add("app.firebase.cert-url", CERTS::url);
    }

    @AfterAll
    static void stop() {
        CERTS.close();
    }

    // ---- opening an account ------------------------------------------------

    @Test
    void a_new_number_opens_a_passwordless_customer_account() throws Exception {
        String phone = "+919000000001";

        String body = signIn(token(phone), "Asha Patel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("Asha Patel"))
                .andExpect(jsonPath("$.user.provider").value("PHONE"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                // The stored address is a placeholder built from the number, and the API
                // must never present a machine identifier as the customer's own e-mail.
                .andExpect(jsonPath("$.user.email").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        User created = userRepository.findById(userIdOf(body)).orElseThrow();
        assertThat(created.getPhoneNumber()).isEqualTo(phone);
        // Already true: an SMS to this number was answered correctly seconds ago, which
        // is exactly what the verification flow exists to establish. Making them prove
        // it again in Account -> Verification would be asking twice for one thing.
        assertThat(created.isPhoneVerified()).isTrue();
        assertThat(created.getPassword()).isNull();
        assertThat(created.isEmailVerified()).isFalse();
        assertThat(created.getEmail()).endsWith("@customers.huevista.local");
    }

    @Test
    void a_sign_in_with_no_name_still_gets_through() throws Exception {
        // A phone token carries no name, and the sign-up screen asks for one but does
        // not insist. A blank must not be the thing that blocks the way in.
        signIn(token("+919000000002"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Customer"));
    }

    // ---- coming back -------------------------------------------------------

    @Test
    void the_same_number_comes_back_to_the_same_account() throws Exception {
        String phone = "+919000000003";

        String first = signIn(token(phone), "Ravi").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = signIn(token(phone), "Someone Else").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // THE case that matters. A customer who bought a room last month must find it
        // waiting, not a new empty account — and the name on a later sign-in must not
        // overwrite the one already on the account.
        assertThat(userIdOf(second)).isEqualTo(userIdOf(first));
        assertThat(objectMapper.readTree(second).at("/user/name").asText()).isEqualTo("Ravi");
        assertThat(userRepository.findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc(phone))
                .hasSize(1);
    }

    @Test
    void a_number_verified_on_an_email_account_signs_into_that_account() throws Exception {
        // The customer registered with an e-mail and password, then verified their
        // mobile under Account -> Verification. Signing in by phone has to reach THAT
        // account: a second account holding half their work is the worst outcome here.
        String phone = "+919000000004";
        User existing = userRepository.save(User.builder()
                .name("Meera Shah")
                .email("meera@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .phoneNumber(phone)
                .phoneVerified(true)
                .build());

        String body = signIn(token(phone), "Ignored").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Meera Shah"))
                // A real address stays visible — only synthesised ones are withheld.
                .andExpect(jsonPath("$.user.email").value("meera@example.com"))
                .andExpect(jsonPath("$.user.provider").value("LOCAL"))
                .andReturn().getResponse().getContentAsString();

        assertThat(userIdOf(body)).isEqualTo(existing.getId());
    }

    @Test
    void an_unverified_number_on_someone_elses_account_is_not_a_way_in() throws Exception {
        // Anyone can type any number into the signup form; nothing has proved it. If an
        // unverified number matched, typing a stranger's number at signup would be all
        // it took to be handed their account when they later signed in by phone.
        String phone = "+919000000005";
        User typedItIn = userRepository.save(User.builder()
                .name("Not Their Number")
                .email("typed@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .phoneNumber(phone)
                .phoneVerified(false)
                .build());

        String body = signIn(token(phone), "Real Owner").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(userIdOf(body)).isNotEqualTo(typedItIn.getId());
        assertThat(userRepository.findById(typedItIn.getId()).orElseThrow().isPhoneVerified()).isFalse();
    }

    @Test
    void a_deleted_account_is_not_reopened_by_its_old_number() throws Exception {
        String phone = "+919000000006";
        User deleted = userRepository.save(User.builder()
                .name("Gone")
                .email("gone@example.com")
                .provider(AuthProvider.LOCAL)
                .role(UserRole.CUSTOMER)
                .phoneNumber(phone)
                .phoneVerified(true)
                .deletedAt(java.time.LocalDateTime.now().minusDays(1))
                .build());

        String body = signIn(token(phone), "Fresh Start").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(userIdOf(body)).isNotEqualTo(deleted.getId());
    }

    // ---- who is turned away ------------------------------------------------

    @Test
    void an_admin_account_must_still_use_email_and_password() throws Exception {
        // An admin password login sends a second factor by e-mail. One SMS must not be
        // able to skip it — a swapped SIM would otherwise be the whole admin console.
        String phone = "+919000000007";
        userRepository.save(User.builder()
                .name("Platform Admin")
                .email("admin-phone@example.com")
                .password(passwordEncoder.encode("password123"))
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN)
                .phoneNumber(phone)
                .phoneVerified(true)
                .build());

        signIn(token(phone), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("email address and password")));
    }

    @Test
    void a_token_from_another_firebase_project_is_refused() throws Exception {
        String forged = Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("uid-attacker")
                .issuer("https://securetoken.google.com/attacker-project")
                .audience().add("attacker-project").and()
                .claim("phone_number", "+919000000008")
                .claim("firebase", FirebaseCerts.phoneProviderClaim("+919000000008"))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(CERTS.privateKey(), Jwts.SIG.RS256)
                .compact();

        signIn(forged, null).andExpect(status().isUnauthorized());
        assertThat(userRepository
                .findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc("+919000000008"))
                .isEmpty();
    }

    @Test
    void a_token_that_did_not_verify_a_number_is_refused() throws Exception {
        // An anonymous Firebase sign-in is free and instant. Without the provider check
        // it would be an account for the asking, with any number the caller cared to
        // put in the claim.
        String anonymous = token(builder -> builder
                .claim("phone_number", "+919000000009")
                .claim("firebase", Map.of("sign_in_provider", "anonymous")));

        signIn(anonymous, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("didn't verify a mobile number")));
    }

    @Test
    void a_phone_token_with_no_number_is_refused() throws Exception {
        String noNumber = token(builder -> builder
                .claim("firebase", Map.of("sign_in_provider", "phone")));

        signIn(noNumber, null).andExpect(status().isUnauthorized());
    }

    @Test
    void a_missing_token_is_a_validation_error() throws Exception {
        mockMvc.perform(post("/api/auth/phone/firebase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers -----------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions signIn(String idToken, String name)
            throws Exception {
        String body = objectMapper.writeValueAsString(
                name == null ? Map.of("idToken", idToken) : Map.of("idToken", idToken, "name", name));
        return mockMvc.perform(post("/api/auth/phone/firebase")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String userIdOf(String responseBody) throws Exception {
        JsonNode node = objectMapper.readTree(responseBody);
        return node.at("/user/id").asText();
    }

    private static String token(String phone) {
        return token(builder -> builder
                .claim("phone_number", phone)
                .claim("firebase", FirebaseCerts.phoneProviderClaim(phone)));
    }

    private static String token(java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customise) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("firebase-uid-" + java.util.UUID.randomUUID())
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("auth_time", Instant.now().minusSeconds(10).getEpochSecond())
                .issuedAt(Date.from(Instant.now().minusSeconds(10)))
                .expiration(Date.from(Instant.now().plusSeconds(3600)));
        customise.accept(builder);
        return builder.signWith(CERTS.privateKey(), Jwts.SIG.RS256).compact();
    }

    private static FirebaseCerts certs() {
        try {
            return new FirebaseCerts();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the stand-in certificate server", e);
        }
    }
}
