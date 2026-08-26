package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.util.Emails;
import com.gridstore.huevista.auth.util.PhoneNumbers;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Sign in with a mobile number, via Firebase Phone Auth.
 *
 * <h2>Where the SMS comes from</h2>
 * Not from us. Delivering a one-time code to an Indian mobile requires a DLT sender
 * and template registration this business does not hold, so {@link com.gridstore.huevista.notification.SmsSender}
 * delivers nothing and every SMS flow in the app is dark. Firebase sends the code on
 * Google's own registered routes, and the browser runs the whole exchange with Firebase
 * directly — this backend never sees a code and never sends one.
 *
 * <h2>What this service does</h2>
 * It turns Google's assertion ("this browser proved control of +919876543210") into a
 * HueVista session, by exactly the same {@code buildAuthResponse} every other sign-in
 * path uses. Two cases:
 *
 * <ul>
 *   <li><b>The number already has an account</b> — because they signed in by phone
 *       before, or because they verified this number on an e-mail account under
 *       Account → Verification. They land on THAT account, with everything in it. This
 *       is the case that matters: a customer who bought a room last month must not get
 *       a new empty account for typing the same number.</li>
 *   <li><b>It doesn't</b> — a passwordless {@link AuthProvider#PHONE} CUSTOMER account
 *       is opened, keyed to the number, with {@code phoneVerified} already true. It has
 *       just been proved, by an SMS, which is the very thing the verification flow
 *       exists to establish.</li>
 * </ul>
 *
 * <h2>Why ADMIN accounts are refused</h2>
 * An admin signing in with a password gets a second factor: a code mailed to them
 * (see {@code AuthService.loginWithOtp}). Letting the same account in through one SMS
 * would make that second factor optional — anybody holding the admin's SIM, or a SIM
 * swapped onto their number, would be past it. So a phone sign-in that resolves to an
 * ADMIN is refused and told to use the e-mail path, where the second factor still runs.
 */
@Service
@Slf4j
public class PhoneAuthService {

    private final FirebaseTokenVerifier verifier;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final com.gridstore.huevista.common.audit.AuditService auditService;

    /**
     * The transaction is opened explicitly, around the database work only.
     *
     * <p>Not {@code @Transactional} on the whole method, because the first thing it does
     * is verify the token — and that can make an outbound HTTPS call to Google. A
     * connection held from the pool across a third party's response time is a connection
     * that is not available to anybody else while Google is having a slow minute.
     */
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public PhoneAuthService(FirebaseTokenVerifier verifier,
                            UserRepository userRepository,
                            AuthService authService,
                            com.gridstore.huevista.common.audit.AuditService auditService,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.verifier = verifier;
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditService = auditService;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Exchange a verified Firebase phone token for a HueVista session, opening an
     * account for the number if it does not have one yet.
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

        // Everything below touches the database; everything above was network and
        // arithmetic. The transaction starts here.
        try {
            return transactions.execute(status -> issueSession(phone, signUpName));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two sign-ins for the same NEW number at once — a double-tapped button on a
            // slow connection is enough. The synthetic address is derived from the
            // number and users.email is UNIQUE, so the loser of the race fails here.
            //
            // The retry has to be a FRESH transaction, not a catch further in: a
            // constraint violation surfaces at flush and leaves the persistence context
            // unusable, so there is nothing to recover inside the failed one. On the
            // second pass the account exists and resolveAccount simply finds it — which
            // is the outcome this request was asking for, so failing it would be
            // reporting a sign-in that actually succeeded as broken.
            log.info("Concurrent first sign-in for {} — retrying against the account the "
                    + "other request opened", PhoneNumbers.mask(phone));
            return transactions.execute(status -> issueSession(phone, signUpName));
        }
    }

    /** The database half: find or open the account, note it, and mint the session. */
    private AuthResponse issueSession(String phone, String signUpName) {
        User user = resolveAccount(phone, signUpName);
        auditService.record(user.getId(), "PHONE_SIGN_IN", "USER", user.getId(),
                PhoneNumbers.mask(phone));
        return authService.buildAuthResponse(user);
    }

    /** The account that owns this number, opening one if the number is new to us. */
    private User resolveAccount(String phone, String signUpName) {
        List<User> owners =
                userRepository.findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc(phone);

        if (owners.isEmpty()) {
            return createAccount(phone, signUpName);
        }
        if (owners.size() > 1) {
            // Possible only from rows written before VerificationService started refusing
            // a number another live account has verified. Signing them in to the oldest
            // is the least surprising answer; the warning is how support finds the pair.
            log.warn("{} live accounts have verified {} — signing in to the oldest ({})",
                    owners.size(), PhoneNumbers.mask(phone), owners.get(0).getId());
        }
        User user = owners.get(0);

        if (user.getRole() == UserRole.ADMIN) {
            log.warn("Refused a phone sign-in for ADMIN account {}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Admin accounts sign in with an email address and password.");
        }
        // A phone sign-in is proof of the number, so it clears a lockout that the
        // password path imposed — the lockout defends a password, and this account
        // holder has just demonstrated they hold the SIM. It does NOT clear a deletion
        // or a merge; those are filtered out of the query above.
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
        log.info("Phone sign-in to existing account {} ({})", user.getId(), user.getProvider());
        return user;
    }

    private User createAccount(String phone, String signUpName) {
        User user = userRepository.save(User.builder()
                .email(Emails.syntheticForPhone(phone))
                .password(null)
                .name(displayName(signUpName))
                .provider(AuthProvider.PHONE)
                .role(UserRole.CUSTOMER)
                // No address was ever given, let alone proved. The placeholder above is
                // a row key, not an inbox — Emails.isSynthetic keeps it out of the API.
                .emailVerified(false)
                .phoneNumber(phone)
                // True because an SMS to this number was answered correctly, seconds ago.
                // That is precisely what the verification flow exists to establish, so
                // making the customer do it again in Account → Verification would be
                // asking them to prove the thing they just proved.
                .phoneVerified(true)
                .build());
        log.info("Opened a phone CUSTOMER account {} for {}", user.getId(), PhoneNumbers.mask(phone));
        return user;
    }

    /** Their name if they gave one; a neutral placeholder otherwise. */
    private static String displayName(String raw) {
        if (raw == null || raw.isBlank()) return "Customer";
        String trimmed = raw.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
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
