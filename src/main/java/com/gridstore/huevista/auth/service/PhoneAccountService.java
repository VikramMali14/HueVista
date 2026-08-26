package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.util.Emails;
import com.gridstore.huevista.auth.util.PhoneNumbers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Turns "this number has been proved" into a HueVista session.
 *
 * <h2>Why this is separate from how the number was proved</h2>
 * There are two ways in — Firebase Phone Auth ({@link PhoneAuthService}) and our own
 * SMS codes ({@link PhoneOtpService}) — and which one a deployment uses is a question
 * about SMS providers and DLT paperwork. It must not also be a question about WHICH
 * ACCOUNT a number opens. Those rules decide whether a returning customer finds their
 * rooms or a stranger's, and they are far too important to exist in two copies that can
 * drift apart. Both callers arrive here having established the same fact, and from that
 * point on nothing differs.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>The number already has an account</b> — from an earlier phone sign-in, or
 *       because it was verified on an e-mail account under Account → Verification. They
 *       land on THAT account, with everything in it. This is the case that matters: a
 *       customer who bought a room last month must not get a new empty account for
 *       typing the same number.</li>
 *   <li><b>It doesn't</b> — a passwordless {@link AuthProvider#PHONE} CUSTOMER account is
 *       opened, keyed to the number, with {@code phoneVerified} already true. It has just
 *       been proved by an SMS, which is the very thing the verification flow exists to
 *       establish.</li>
 *   <li><b>Only VERIFIED numbers match.</b> Anyone can type any number into the signup
 *       form and nothing has proved it. If an unverified number matched, typing a
 *       stranger's number at signup would be all it took to be handed their account when
 *       they later signed in by phone.</li>
 *   <li><b>ADMIN accounts are refused.</b> An admin signing in with a password gets a
 *       second factor mailed to them (see {@code AuthService.loginWithOtp}). Letting the
 *       same account in through one SMS would make that second factor optional — anybody
 *       holding the admin's SIM, or a SIM swapped onto their number, would be past it.</li>
 * </ul>
 */
@Service
@Slf4j
public class PhoneAccountService {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final com.gridstore.huevista.common.audit.AuditService auditService;

    /**
     * The transaction is opened explicitly, around the database work only.
     *
     * <p>Not {@code @Transactional} on the entry point, because both callers do network
     * work first — Firebase verification fetches Google's certificates, and an SMS send
     * calls a provider. A connection held from the pool across a third party's response
     * time is a connection nobody else can use while that third party is slow.
     */
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public PhoneAccountService(UserRepository userRepository,
                               AuthService authService,
                               com.gridstore.huevista.common.audit.AuditService auditService,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditService = auditService;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Sign in the account that owns {@code phone}, opening one if the number is new.
     *
     * @param phone      a NORMALIZED number the caller has already proved
     * @param signUpName what to call them, used only when opening a new account
     * @param how        which flow proved it, for the audit trail
     */
    public AuthResponse signInWithProvenNumber(String phone, String signUpName, String how) {
        try {
            return transactions.execute(status -> issueSession(phone, signUpName, how));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two sign-ins for the same NEW number at once — a double-tapped button on a
            // slow connection is enough. The synthetic address is derived from the number
            // and users.email is UNIQUE, so the loser of the race fails here.
            //
            // The retry has to be a FRESH transaction, not a catch further in: a
            // constraint violation surfaces at flush and leaves the persistence context
            // unusable, so there is nothing to recover inside the failed one. On the
            // second pass the account exists and resolveAccount simply finds it — which
            // is the outcome this request was asking for, so failing it would be
            // reporting a sign-in that actually succeeded as broken.
            log.info("Concurrent first sign-in for {} — retrying against the account the "
                    + "other request opened", PhoneNumbers.mask(phone));
            return transactions.execute(status -> issueSession(phone, signUpName, how));
        }
    }

    /** The database half: find or open the account, note it, and mint the session. */
    private AuthResponse issueSession(String phone, String signUpName, String how) {
        User user = resolveAccount(phone, signUpName);
        auditService.record(user.getId(), "PHONE_SIGN_IN", "USER", user.getId(),
                how + " " + PhoneNumbers.mask(phone));
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
}
