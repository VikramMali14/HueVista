package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.dto.PhoneOtpStatusResponse;
import com.gridstore.huevista.auth.model.PhoneLoginCode;
import com.gridstore.huevista.auth.repository.PhoneLoginCodeRepository;
import com.gridstore.huevista.auth.util.PhoneNumbers;
import com.gridstore.huevista.notification.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sign in with a mobile number, proved by a code we send ourselves.
 *
 * <h2>Why this exists alongside Firebase</h2>
 * Firebase needs no DLT registration, which is why it exists — and charges about ₹6 a
 * message for the privilege. Once a DLT registration is in place, an Indian aggregator
 * carries the same message for around 20 paise, thirty times cheaper. Both flows prove
 * the same fact and hand off to the same {@link PhoneAccountService}, so a deployment can
 * move from one to the other by changing configuration and nothing else.
 *
 * <h2>Every send here spends real money on somebody else's phone</h2>
 * That is the difference from the Firebase path, where Google throttles the SMS before
 * our server is ever reached. Here we are the sender, so the limits are ours to enforce,
 * and they are not optional:
 *
 * <ul>
 *   <li><b>A cooldown</b> between codes to one number, measured from the last code SENT
 *       regardless of what became of it — a cooldown that a successful verification
 *       resets is one an attacker can clear at will.</li>
 *   <li><b>A daily cap</b> per number. The cooldown alone only paces an attacker; it does
 *       not stop them texting a stranger all night at our expense. This does.</li>
 *   <li><b>An attempt limit</b> per code, under a row lock, so parallel guesses cannot
 *       each read the counter before any of them writes it.</li>
 *   <li>Per-IP throttling, in {@code SensitiveEndpointRateLimitFilter}.</li>
 * </ul>
 *
 * <p>The caps are per NUMBER rather than per account on purpose. There may be no account
 * — that is the point of the flow — and an attacker picking a stranger's number would
 * otherwise face no limit at all.
 *
 * <h2>What the response does not say</h2>
 * The send step answers identically whether or not the number has an account. It has to:
 * this endpoint is public, and an answer that differed would turn it into a free tool for
 * asking whether a given person is a HueVista customer.
 */
@Service
@Slf4j
public class PhoneOtpService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 5;

    private final PhoneLoginCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsSender smsSender;
    private final PhoneAccountService accounts;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public PhoneOtpService(PhoneLoginCodeRepository codeRepository,
                           PasswordEncoder passwordEncoder,
                           SmsSender smsSender,
                           PhoneAccountService accounts,
                           org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.codeRepository = codeRepository;
        this.passwordEncoder = passwordEncoder;
        this.smsSender = smsSender;
        this.accounts = accounts;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * How many codes one number may be sent in a day.
     *
     * <p>A real person asking for a code, mistyping it, and asking again uses two or
     * three. Ten leaves that alone completely and still caps what one number can cost —
     * and, more to the point, caps how far somebody can be pestered by an endpoint that
     * anyone on the internet can call.
     */
    @Value("${app.sms.otp.max-per-number-per-day:10}")
    private int maxPerNumberPerDay;

    /**
     * Whether this deployment can sign anybody in this way.
     *
     * <p>False when no SMS provider is configured — the codes would go to the server log,
     * where the customer cannot read them. Better to hide the option than to offer a
     * sign-in that silently cannot complete.
     */
    public boolean isEnabled() {
        return smsSender.deliversForReal();
    }

    /** Send a sign-in code to {@code rawPhone}. */
    @Transactional
    public PhoneOtpStatusResponse send(String rawPhone, String signUpName) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Signing in with a mobile number isn't available right now. Please use your email.");
        }
        String phone = normalized(rawPhone);

        codeRepository.findTopByPhoneNumberOrderByCreatedAtDesc(phone).ifPresent(last -> {
            long since = Duration.between(last.getCreatedAt(), LocalDateTime.now()).getSeconds();
            if (since < COOLDOWN.getSeconds()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait " + (COOLDOWN.getSeconds() - since) + "s before asking for another code.");
            }
        });

        long today = codeRepository.countByPhoneNumberAndCreatedAtAfter(phone, LocalDateTime.now().minusDays(1));
        if (today >= maxPerNumberPerDay) {
            // Deliberately not "you have had 10 today": the caller may not be the person
            // holding the phone, and how close they are to a limit is not their business.
            log.warn("Daily SMS cap reached for {} ({} in 24h)", PhoneNumbers.mask(phone), today);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "That number has been sent too many codes today. Please try again tomorrow, "
                            + "or sign in with your email.");
        }

        // Only the newest code works. Without this, every code sent today stays live for
        // its full ten minutes and an attacker gets ten times the guesses.
        List<PhoneLoginCode> prior = codeRepository.findByPhoneNumberAndConsumedFalse(phone);
        prior.forEach(c -> c.setConsumed(true));
        codeRepository.saveAll(prior);

        String code = String.format("%06d", random.nextInt(1_000_000));
        codeRepository.save(PhoneLoginCode.builder()
                .phoneNumber(phone)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(LocalDateTime.now().plus(TTL))
                .signUpName(trimmedName(signUpName))
                .attempts(0)
                .consumed(false)
                .build());

        smsSender.sendOtp(phone, code, (int) TTL.toMinutes());
        log.info("Sign-in code sent to {}", PhoneNumbers.mask(phone));

        return PhoneOtpStatusResponse.builder()
                .destination(PhoneNumbers.mask(phone))
                .expiresInSeconds((int) TTL.getSeconds())
                .cooldownSeconds((int) COOLDOWN.getSeconds())
                .build();
    }

    /** The outcome of checking a code: either it was good, or here is why it was not. */
    private record CodeCheck(boolean ok, String signUpName, HttpStatus status, String message) {
        static CodeCheck good(String signUpName) {
            return new CodeCheck(true, signUpName, null, null);
        }
        static CodeCheck bad(HttpStatus status, String message) {
            return new CodeCheck(false, null, status, message);
        }
    }

    /**
     * Check the code and sign in.
     *
     * <h2>Two transactions, deliberately</h2>
     * Checking the code and opening the account want opposite things from a failure, and
     * one transaction cannot give both.
     *
     * <p>A wrong code must still COMMIT its attempts++ — rolled back, the counter never
     * rises and the attempt limit is decorative. A refusal from
     * {@link PhoneAccountService} (an ADMIN account) must roll back cleanly instead.
     *
     * <p>The first version tried to have it both ways with {@code noRollbackFor} over the
     * whole method, and the two collided: the nested transaction marked itself
     * rollback-only on the admin refusal, the outer one then tried to commit anyway, and
     * the caller got {@code UnexpectedRollbackException} — a 500 where a 403 belonged.
     * Every {@code @Transactional} test missed it, because a test transaction is rolled
     * back at the end and never commits, which is the exact moment the conflict surfaces.
     *
     * <p>So the code check runs in its own transaction and NEVER throws — it returns what
     * it found, commits either way, and the throwing happens out here. Account resolution
     * then opens its own transaction with nothing nested inside it.
     */
    public AuthResponse verify(String rawPhone, String codeInput) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Signing in with a mobile number isn't available right now. Please use your email.");
        }
        String phone = normalized(rawPhone);

        CodeCheck check = transactions.execute(status -> consume(phone, codeInput));
        if (check == null || !check.ok()) {
            HttpStatus status = check == null ? HttpStatus.BAD_REQUEST : check.status();
            String message = check == null ? "Ask for a code first." : check.message();
            throw new ResponseStatusException(status, message);
        }

        return accounts.signInWithProvenNumber(phone, check.signUpName(), "sms");
    }

    /**
     * Spend the code if it is right, count the attempt if it is not — and commit either
     * way. Returns rather than throws, so that nothing it decides can roll back the
     * bookkeeping it just did.
     */
    private CodeCheck consume(String phone, String codeInput) {
        List<PhoneLoginCode> active = codeRepository.findActiveForUpdate(phone);
        if (active.isEmpty()) {
            return CodeCheck.bad(HttpStatus.BAD_REQUEST, "Ask for a code first.");
        }
        PhoneLoginCode candidate = active.get(0);

        if (candidate.getExpiresAt().isBefore(LocalDateTime.now())) {
            candidate.setConsumed(true);
            codeRepository.save(candidate);
            return CodeCheck.bad(HttpStatus.BAD_REQUEST, "That code has expired. Ask for a new one.");
        }
        if (candidate.getAttempts() >= MAX_ATTEMPTS) {
            candidate.setConsumed(true);
            codeRepository.save(candidate);
            return CodeCheck.bad(HttpStatus.BAD_REQUEST, "Too many incorrect attempts. Ask for a new code.");
        }

        String code = codeInput == null ? "" : codeInput.trim();
        if (!passwordEncoder.matches(code, candidate.getCodeHash())) {
            candidate.setAttempts(candidate.getAttempts() + 1);
            codeRepository.save(candidate);
            int left = Math.max(0, MAX_ATTEMPTS - candidate.getAttempts());
            return CodeCheck.bad(HttpStatus.BAD_REQUEST,
                    "Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left.");
        }

        // Consumed BEFORE the session is issued, and committed before account resolution
        // is even attempted. If anything downstream fails the code is spent either way —
        // a code that survives its own successful use is a code that can be replayed.
        candidate.setConsumed(true);
        codeRepository.save(candidate);
        return CodeCheck.good(candidate.getSignUpName());
    }

    private static String trimmedName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private static String normalized(String raw) {
        String phone;
        try {
            phone = PhoneNumbers.normalize(raw);
        } catch (IllegalArgumentException e) {
            phone = null;
        }
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter a valid mobile number with country code, e.g. +9198…");
        }
        return phone;
    }
}
