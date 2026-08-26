package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.util.PhoneNumbers;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign in with a mobile number, proved by Firebase Phone Auth.
 *
 * <h2>Where the SMS comes from</h2>
 * Not from us. Firebase sends the one-time code on Google's own registered routes and
 * checks it in the browser, which is what makes this work with no DLT registration at
 * all. This backend never sees a code and never sends one; what reaches it is Google's
 * signed assertion that a browser proved control of a number.
 *
 * <h2>What this class is responsible for</h2>
 * Exactly one thing: deciding whether that assertion is genuine and ours. Which ACCOUNT
 * the number then opens is {@link PhoneAccountService}'s job, shared with
 * {@link PhoneOtpService} — the rules that decide whether a returning customer finds
 * their rooms must not depend on which SMS provider a deployment happens to use.
 *
 * <h2>Cost</h2>
 * Firebase charges per SMS — around USD 0.07 in India, roughly ₹6, on a Blaze billing
 * account it will not send without. That is the price of not needing a DLT registration.
 * {@link PhoneOtpService} is the cheap path for once one exists.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PhoneAuthService {

    private final FirebaseTokenVerifier verifier;
    private final PhoneAccountService accounts;

    /** True when this deployment can accept a Firebase phone sign-in at all. */
    public boolean isEnabled() {
        return verifier.isConfigured();
    }

    /**
     * Exchange a verified Firebase phone token for a HueVista session.
     *
     * @param idToken    the Firebase ID token from the browser
     * @param signUpName what to call them, used only when creating a new account
     */
    public AuthResponse signIn(String idToken, String signUpName) {
        if (!verifier.isConfigured()) {
            // Nothing is half-configured here: with no project id there is no way to
            // tell our project's tokens from anyone else's, so the endpoint is off
            // rather than lenient.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Signing in with a mobile number isn't available right now. Please use your email.");
        }

        Claims claims;
        try {
            claims = verifier.verify(idToken);
        } catch (FirebaseTokenVerifier.FirebaseTokenException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        // The token must be a PHONE sign-in. The same Firebase project can also mint
        // tokens for anonymous, e-mail-link or federated sign-ins, and those prove
        // nothing about a mobile number — an anonymous token would otherwise be an
        // account for the asking.
        String provider = FirebaseTokenVerifier.signInProviderOf(claims);
        if (!FirebaseTokenVerifier.PHONE_PROVIDER.equals(provider)) {
            log.warn("Refused a Firebase token signed in via '{}' at the phone endpoint", provider);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That sign-in didn't verify a mobile number. Please try again.");
        }

        String phone = normalized(claims.get("phone_number", String.class));
        return accounts.signInWithProvenNumber(phone, signUpName, "firebase");
    }

    /**
     * The number from the token, in the form the users table stores.
     *
     * <p>Firebase always issues E.164, so this is agreement-keeping rather than
     * cleanup — but it is the ONE place the stored form is decided for this flow, and
     * a number that reaches the database in a different shape than the verification
     * flow writes is a number that silently opens a second account.
     */
    private static String normalized(String claim) {
        String phone;
        try {
            phone = PhoneNumbers.normalize(claim);
        } catch (IllegalArgumentException e) {
            phone = null;
        }
        if (phone == null) {
            log.warn("A verified Firebase token carried no usable phone_number claim");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That sign-in didn't verify a mobile number. Please try again.");
        }
        return phone;
    }
}
