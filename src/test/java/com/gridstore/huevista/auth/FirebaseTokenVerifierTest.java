package com.gridstore.huevista.auth;

import com.gridstore.huevista.auth.service.FirebaseTokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The verifier is the whole security boundary of phone sign-in: everything downstream
 * treats its output as "Google says this browser controls this number". Each test here
 * is one way an attacker gets a session on somebody else's account if the check is
 * skipped or done loosely.
 */
class FirebaseTokenVerifierTest {

    private static final String PROJECT = "huevista-test";

    private static FirebaseCerts certs;
    private static FirebaseTokenVerifier verifier;

    @BeforeAll
    static void start() throws Exception {
        certs = new FirebaseCerts();
        verifier = new FirebaseTokenVerifier(PROJECT, certs.url());
    }

    @AfterAll
    static void stop() {
        certs.close();
    }

    // ---- the happy path ----------------------------------------------------

    @Test
    void accepts_a_genuine_phone_token_for_this_project() {
        Claims claims = verifier.verify(token(builder -> {}));

        assertThat(claims.getSubject()).isEqualTo("firebase-uid-1");
        assertThat(claims.get("phone_number", String.class)).isEqualTo("+919876543210");
        assertThat(FirebaseTokenVerifier.signInProviderOf(claims)).isEqualTo("phone");
    }

    @Test
    void reads_the_sign_in_provider_out_of_the_nested_firebase_claim() {
        Claims claims = verifier.verify(token(b -> b.claim("firebase",
                Map.of("sign_in_provider", "anonymous"))));

        // The verifier's job stops at authenticity; refusing a non-phone sign-in is
        // PhoneAuthService's, and it needs this to be reported accurately.
        assertThat(FirebaseTokenVerifier.signInProviderOf(claims)).isEqualTo("anonymous");
    }

    // ---- the ways in that must stay shut -----------------------------------

    @Test
    void refuses_a_token_issued_to_another_firebase_project() {
        // THE attack this endpoint invites. Anyone can create a Firebase project in a
        // minute and sign in to it as any number they hold — including one they read
        // off a customer's account. Such a token is signed by these very same Google
        // keys, so the signature proves nothing on its own: only `aud` does.
        assertThatThrownBy(() -> verifier.verify(token(b -> b.audience().add("someone-elses-project").and())))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class)
                .hasMessageContaining("not issued for this app");
    }

    @Test
    void refuses_a_token_whose_issuer_is_not_our_project() {
        assertThatThrownBy(() -> verifier.verify(
                token(b -> b.issuer("https://securetoken.google.com/some-other-project"))))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class)
                .hasMessageContaining("not issued for this app");
    }

    @Test
    void refuses_an_expired_token() {
        assertThatThrownBy(() -> verifier.verify(token(b -> b
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600))))))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class)
                .hasMessageContaining("expired or is not valid");
    }

    @Test
    void refuses_a_token_signed_with_a_key_google_does_not_publish() throws Exception {
        // A self-signed token with a real kid: the forger controls the private half, so
        // only looking the kid up in Google's key set catches it.
        var stranger = KeyPairGenerator.getInstance("RSA");
        stranger.initialize(2048);
        String forged = Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("firebase-uid-1")
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("phone_number", "+919876543210")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(stranger.generateKeyPair().getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verify(forged))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
    }

    @Test
    void refuses_a_token_whose_kid_is_unknown() {
        assertThatThrownBy(() -> verifier.verify(token(b -> b.header().keyId("not-a-real-kid").and())))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
    }

    @Test
    void refuses_an_hmac_signed_token() {
        // Algorithm confusion: sign with HS256 using something the server might hand the
        // parser as a key. The alg check in the key locator has to refuse before any
        // signature work happens.
        SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "a-thoroughly-inadequate-but-long-enough-test-secret".getBytes());
        String forged = Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("firebase-uid-1")
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("phone_number", "+919876543210")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> verifier.verify(forged))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
    }

    @Test
    void refuses_an_unsigned_token() {
        String unsigned = Jwts.builder()
                .subject("firebase-uid-1")
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("phone_number", "+919876543210")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .compact();

        assertThatThrownBy(() -> verifier.verify(unsigned))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
    }

    @Test
    void refuses_a_missing_or_garbled_token() {
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
        assertThatThrownBy(() -> verifier.verify("  "))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
        assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
    }

    // ---- the certificate cache ---------------------------------------------

    @Test
    void an_unknown_kid_cannot_be_turned_into_a_fetch_per_request() throws Exception {
        // A kid is attacker-controlled and is never in the cache, so "refresh once on an
        // unknown kid, in case Google rotated" is a lever anybody can pull. Unthrottled it
        // is one HTTPS round trip to Google per request, each inside the verifier's lock,
        // so every legitimate sign-in queues behind it holding a database connection.
        //
        // This pins the throttle. The first version keyed it off the cache expiry, which
        // an unknown kid never reaches — with Google's real max-age the guard evaluated
        // to a time in the past and never engaged at all.
        try (FirebaseCerts own = new FirebaseCerts()) {
            FirebaseTokenVerifier v = new FirebaseTokenVerifier(PROJECT, own.url());

            // Prime the cache with one good verification.
            v.verify(tokenFor(own, b -> {}));
            int afterPriming = own.fetchCount();
            assertThat(afterPriming).isEqualTo(1);

            for (int i = 0; i < 25; i++) {
                final int n = i;
                assertThatThrownBy(() -> v.verify(tokenFor(own, b -> b.header().keyId("unknown-" + n).and())))
                        .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class);
            }

            assertThat(own.fetchCount())
                    .as("25 unknown key ids must not become 25 outbound requests")
                    .isEqualTo(afterPriming);
        }
    }

    @Test
    void a_cached_certificate_set_serves_many_verifications_from_one_fetch() throws Exception {
        try (FirebaseCerts own = new FirebaseCerts()) {
            FirebaseTokenVerifier v = new FirebaseTokenVerifier(PROJECT, own.url());
            for (int i = 0; i < 10; i++) {
                v.verify(tokenFor(own, b -> {}));
            }
            assertThat(own.fetchCount()).isEqualTo(1);
        }
    }

    @Test
    void an_unreachable_certificate_endpoint_is_reported_as_such_not_as_a_bad_token() throws Exception {
        FirebaseCerts dead = new FirebaseCerts();
        String url = dead.url();
        String token = tokenFor(dead, b -> {});
        dead.close();

        FirebaseTokenVerifier v = new FirebaseTokenVerifier(PROJECT, url);

        // Nothing to check against, and the token may well be perfectly good — the
        // message has to say "we couldn't check", not "yours is invalid".
        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class)
                .hasMessageContaining("Could not check");

        // And the retry must not hammer a service that is already down.
        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(FirebaseTokenVerifier.FirebaseTokenException.class)
                .hasMessageContaining("Could not check");
    }

    // ---- configuration -----------------------------------------------------

    @Test
    void is_off_until_a_project_id_is_configured() {
        FirebaseTokenVerifier unconfigured = new FirebaseTokenVerifier("  ", certs.url());

        assertThat(unconfigured.isConfigured()).isFalse();
        // Refusing outright, rather than accepting any project's token, is the point:
        // with no project id there is nothing to check `aud` against.
        assertThatThrownBy(() -> unconfigured.verify(token(b -> {})))
                .isInstanceOf(IllegalStateException.class);
        assertThat(verifier.isConfigured()).isTrue();
    }

    // ---- helper ------------------------------------------------------------

    /** A well-formed token for this project signed by a GIVEN certificate server. */
    private static String tokenFor(FirebaseCerts source,
                                   java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customise) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("firebase-uid-1")
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("phone_number", "+919876543210")
                .claim("firebase", FirebaseCerts.phoneProviderClaim("+919876543210"))
                .issuedAt(Date.from(Instant.now().minusSeconds(30)))
                .expiration(Date.from(Instant.now().plusSeconds(3600)));
        customise.accept(builder);
        return builder.signWith(source.privateKey(), Jwts.SIG.RS256).compact();
    }

    /** A well-formed token for this project, with the given overrides applied last. */
    private static String token(java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customise) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("firebase-uid-1")
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("phone_number", "+919876543210")
                .claim("firebase", FirebaseCerts.phoneProviderClaim("+919876543210"))
                .claim("auth_time", Instant.now().minusSeconds(30).getEpochSecond())
                .issuedAt(Date.from(Instant.now().minusSeconds(30)))
                .expiration(Date.from(Instant.now().plusSeconds(3600)));
        customise.accept(builder);
        return builder.signWith(certs.privateKey(), Jwts.SIG.RS256).compact();
    }
}
