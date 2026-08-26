package com.gridstore.huevista.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Verifies a Firebase Authentication ID token, offline, against Google's public keys.
 *
 * <h2>Why this exists</h2>
 * Sending an SMS one-time code to an Indian mobile number needs a DLT registration
 * (TRAI's sender/template registry) that this business does not have, so {@code SmsSender}
 * delivers nothing — see its class comment. Firebase Phone Auth sends that SMS on
 * Google's own registered routes instead, and the browser does the whole code dance
 * with Firebase directly. What reaches this backend at the end of it is an ID token:
 * a short-lived JWT, signed by Google, asserting "this browser proved control of
 * {@code +919876543210}".
 *
 * <p>That assertion is the ONLY thing standing between an anonymous caller and a
 * session on somebody's account, so it is checked in full here and nothing about it
 * is taken on trust.
 *
 * <h2>Why not the Firebase Admin SDK</h2>
 * {@code firebase-admin} does exactly this check, and drags in gRPC, Google Cloud
 * Storage and a service-account private key to do it. Verifying an ID token needs no
 * credential at all — only the project id, which is public and already baked into the
 * frontend bundle. Doing it with the JJWT already on the classpath adds no dependency
 * and, more importantly, no secret to leak, rotate or forget to configure.
 *
 * <h2>What is checked</h2>
 * Per Google's published rules for verifying ID tokens manually:
 * <ul>
 *   <li>Signature — RS256 against the x509 certificate matching the token's {@code kid},
 *       fetched from Google's public endpoint and cached for as long as the response's
 *       {@code Cache-Control: max-age} says it stays valid.</li>
 *   <li>{@code alg} is RS256. Refused in the key locator, before any signature work,
 *       so a token that names {@code none} or an HMAC algorithm can never reach the
 *       parser with a key it might be verified against.</li>
 *   <li>{@code exp} in the future and {@code iat} in the past — enforced by JJWT with a
 *       small clock skew allowance.</li>
 *   <li>{@code aud} equals the project id, and {@code iss} is
 *       {@code https://securetoken.google.com/<project id>}. Without BOTH, an ID token
 *       minted by any OTHER Firebase project on earth — one the attacker owns and can
 *       put any phone number into — would verify against these same Google keys.</li>
 *   <li>{@code sub} is present and non-empty (the Firebase uid).</li>
 *   <li>{@code auth_time} is in the past.</li>
 * </ul>
 *
 * <p>The phone number and sign-in provider are read by the caller from the returned
 * claims; this class's job ends at "these claims are genuinely Google's, and ours".
 */
@Service
@Slf4j
public class FirebaseTokenVerifier {

    /**
     * Google's public x509 certificates for Firebase ID tokens, keyed by {@code kid}.
     * Documented and stable; the certificates behind it rotate every few hours, which
     * is what the {@code max-age} honouring below is for.
     */
    public static final String GOOGLE_CERT_URL =
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

    private static final String ISSUER_PREFIX = "https://securetoken.google.com/";

    /** Tolerance for a client clock that disagrees with ours. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    /**
     * Floor for how long fetched certificates are cached, used when the response
     * carries no usable {@code max-age}. Google's own guidance is to respect the
     * header; this only keeps a missing header from turning every sign-in into an
     * outbound HTTPS round trip.
     */
    private static final Duration MIN_CACHE = Duration.ofMinutes(5);

    /** Ceiling, so a nonsense {@code max-age} can't pin a rotated-out key forever. */
    private static final Duration MAX_CACHE = Duration.ofHours(24);

    /** What a phone sign-in's token must name as the provider that authenticated it. */
    public static final String PHONE_PROVIDER = "phone";

    private final String projectId;
    private final String certUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Cached certificates and the instant they stop being trusted. Guarded by {@code this}. */
    private Map<String, PublicKey> cachedKeys = Collections.emptyMap();
    private Instant cachedUntil = Instant.EPOCH;

    /**
     * @param certUrl where the signing certificates come from. Overridable only so the
     *                tests can serve a keypair they control and exercise the real
     *                fetch/parse/cache path; leave it at the default in every
     *                deployment — pointing it anywhere but Google means trusting
     *                whoever answers to say which keys sign your users in.
     */
    public FirebaseTokenVerifier(@Value("${app.firebase.project-id:}") String projectId,
                                 @Value("${app.firebase.cert-url:" + GOOGLE_CERT_URL + "}") String certUrl) {
        this.projectId = projectId == null ? "" : projectId.trim();
        this.certUrl = certUrl == null || certUrl.isBlank() ? GOOGLE_CERT_URL : certUrl.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** False when no project id is configured — phone sign-in is then simply off. */
    public boolean isConfigured() {
        return !projectId.isEmpty();
    }

    public String getProjectId() {
        return projectId;
    }

    /**
     * The verified claims of a genuine, unexpired ID token issued by OUR Firebase project.
     *
     * @throws IllegalStateException    when no project id is configured
     * @throws FirebaseTokenException   when the token is missing, malformed, expired,
     *                                  signed by the wrong key, or issued to another project
     */
    public Claims verify(String idToken) {
        if (!isConfigured()) {
            throw new IllegalStateException("Firebase phone sign-in is not configured on this server.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new FirebaseTokenException("No sign-in token was supplied.");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(header -> keyFor(header.getAlgorithm(), String.valueOf(header.get("kid"))))
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // Deliberately vague to the caller, specific in the log: the client can do
            // nothing with "kid abc123 is not in Google's key set" except learn how the
            // check works, and an expired token and a forged one get the same answer.
            log.warn("Firebase ID token rejected: {}", e.getMessage());
            throw new FirebaseTokenException("That sign-in has expired or is not valid. Please try again.");
        }

        // aud + iss are what tie the token to OUR project. The signature alone only says
        // "some Firebase project issued this", and anyone can create a Firebase project
        // and sign in to it as any number they control — including one they have just
        // read off a customer's account.
        //
        // EQUALS, not contains. Firebase mints exactly one audience — the project the
        // user signed in to — so a token naming ours alongside anything else did not
        // come out of the normal flow, and "our id is in there somewhere" is a weaker
        // question than the one Google's own verification rules ask.
        Set<String> audience = claims.getAudience();
        if (audience == null || audience.size() != 1 || !audience.contains(projectId)) {
            log.warn("Firebase ID token was issued to another project (aud={})", audience);
            throw new FirebaseTokenException("That sign-in was not issued for this app.");
        }
        if (!(ISSUER_PREFIX + projectId).equals(claims.getIssuer())) {
            log.warn("Firebase ID token has the wrong issuer: {}", claims.getIssuer());
            throw new FirebaseTokenException("That sign-in was not issued for this app.");
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new FirebaseTokenException("That sign-in is not valid.");
        }
        // A token can carry an auth_time later than now only if it was minted against a
        // clock we should not believe.
        Object authTime = claims.get("auth_time");
        if (authTime instanceof Number n
                && Instant.ofEpochSecond(n.longValue()).isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new FirebaseTokenException("That sign-in is not valid.");
        }
        return claims;
    }

    /** The {@code firebase.sign_in_provider} claim — "phone" for a phone sign-in. */
    @SuppressWarnings("unchecked")
    public static String signInProviderOf(Claims claims) {
        Object firebase = claims.get("firebase");
        if (firebase instanceof Map<?, ?> map) {
            Object provider = ((Map<String, Object>) map).get("sign_in_provider");
            return provider == null ? null : String.valueOf(provider);
        }
        return null;
    }

    /**
     * The signing key for this token's {@code kid}, refreshing the cache once if the
     * key is unknown — Google rotates certificates, and a token signed with a key
     * minted since our last fetch is legitimate, not forged.
     */
    private Key keyFor(String algorithm, String kid) {
        // Refuse anything but RS256 HERE rather than after the fact. Handing the parser
        // a key for a token whose header says "none" or "HS256" is how algorithm-confusion
        // attacks start; there is no reason for this endpoint to accept either.
        if (!"RS256".equals(algorithm)) {
            throw new FirebaseTokenException("That sign-in is not valid.");
        }
        if (kid == null || kid.isBlank() || "null".equals(kid)) {
            throw new FirebaseTokenException("That sign-in is not valid.");
        }
        PublicKey key = currentKeys(false).get(kid);
        if (key == null) {
            key = currentKeys(true).get(kid);
        }
        if (key == null) {
            throw new FirebaseTokenException("That sign-in is not valid.");
        }
        return key;
    }

    /**
     * The cached certificate set, fetched when it is stale (or when {@code force} says
     * a key we have never seen has turned up).
     *
     * <p>Synchronized so a burst of sign-ins after an expiry makes ONE outbound request
     * rather than one per request. The lock is held across the fetch on purpose: the
     * alternative — every thread fetching — is worse than a brief queue, and the fetch
     * has a hard timeout.
     */
    private synchronized Map<String, PublicKey> currentKeys(boolean force) {
        if (!force && Instant.now().isBefore(cachedUntil) && !cachedKeys.isEmpty()) {
            return cachedKeys;
        }
        // A forced refresh that has just happened is a repeat of the same miss, not a
        // rotation — don't let an unknown kid become an unbounded fetch loop.
        if (force && Instant.now().isBefore(cachedUntil.minus(MAX_CACHE).plus(MIN_CACHE))) {
            return cachedKeys;
        }
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(certUrl))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Google returned HTTP " + response.statusCode());
            }
            Map<String, PublicKey> parsed = new HashMap<>();
            JsonNode body = mapper.readTree(response.body());
            body.fields().forEachRemaining(entry -> {
                PublicKey key = publicKeyFrom(entry.getValue().asText());
                if (key != null) parsed.put(entry.getKey(), key);
            });
            if (parsed.isEmpty()) {
                throw new IllegalStateException("Google's certificate list was empty");
            }
            cachedKeys = Map.copyOf(parsed);
            cachedUntil = Instant.now().plus(cacheFor(response));
            log.debug("Refreshed {} Firebase signing certificate(s), good until {}", parsed.size(), cachedUntil);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseTokenException("Could not check that sign-in right now. Please try again.");
        } catch (Exception e) {
            // Serving the stale set beats refusing every sign-in during a blip: the keys
            // we hold are still genuinely Google's, and every other check still applies.
            if (!cachedKeys.isEmpty()) {
                log.warn("Could not refresh Firebase signing certificates ({}), using the cached set", e.toString());
                return cachedKeys;
            }
            log.error("Could not fetch Firebase signing certificates: {}", e.toString());
            throw new FirebaseTokenException("Could not check that sign-in right now. Please try again.");
        }
        return cachedKeys;
    }

    /** How long Google says the certificates stay good, clamped to something sane. */
    private static Duration cacheFor(HttpResponse<String> response) {
        Duration maxAge = response.headers().firstValue("cache-control")
                .map(value -> {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("max-age\\s*=\\s*(\\d+)").matcher(value.toLowerCase());
                    return m.find() ? Duration.ofSeconds(Long.parseLong(m.group(1))) : null;
                })
                .orElse(null);
        if (maxAge == null || maxAge.compareTo(MIN_CACHE) < 0) return MIN_CACHE;
        return maxAge.compareTo(MAX_CACHE) > 0 ? MAX_CACHE : maxAge;
    }

    private static PublicKey publicKeyFrom(String pem) {
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            return cert.getPublicKey();
        } catch (Exception e) {
            log.warn("Skipping an unreadable Firebase certificate: {}", e.toString());
            return null;
        }
    }

    /** A token that did not check out. Mapped to 401 by the controller. */
    public static class FirebaseTokenException extends RuntimeException {
        public FirebaseTokenException(String message) {
            super(message);
        }
    }
}
