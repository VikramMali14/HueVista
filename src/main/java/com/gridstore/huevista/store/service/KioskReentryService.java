package com.gridstore.huevista.store.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.repository.CustomerAccessCodeRepository;
import com.gridstore.huevista.account.service.GuestAccountService;
import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.AuthService;
import com.gridstore.huevista.auth.util.Emails;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.store.dto.KioskReentryStatusResponse;
import com.gridstore.huevista.store.model.KioskReentryCode;
import com.gridstore.huevista.store.repository.KioskReentryCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Getting a kiosk buyer back to what they paid for, days later and on another phone.
 *
 * <p>The route in is the address they gave at the till, not the code on the receipt.
 * Since a redeemed code never expires, treating it as the credential would make a till
 * slip a permanent password to a stranger's account — one that can be photographed,
 * dropped, or simply read off the counter. An e-mailed code proves the person asking
 * can reach the mailbox the purchase was made under, and leaves the eight characters
 * doing their real job: telling the shop which paint to mix.
 *
 * <p>Every path through {@link #requestCode} returns the same thing. Whether the
 * address bought anything, whether the account is claimable, and whether a code was
 * suppressed by the cooldown are all invisible to the caller — otherwise this becomes
 * a lookup for "has this person shopped here", answerable by anyone holding an address.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KioskReentryService {

    private final CustomerAccessCodeRepository codeRepository;
    private final KioskReentryCodeRepository reentryRepository;
    private final UserRepository userRepository;
    private final GuestAccountService guestAccountService;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final AuditService auditService;

    private static final Duration TTL = Duration.ofMinutes(20);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Send a sign-in code, if there is anything to send one for. Never says which. */
    @Transactional
    public KioskReentryStatusResponse requestCode(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        KioskReentryStatusResponse silent = KioskReentryStatusResponse.builder()
                .expiresInSeconds((int) TTL.getSeconds())
                .cooldownSeconds((int) COOLDOWN.getSeconds())
                .build();

        if (email == null || email.isBlank()) return silent;

        CustomerAccessCode code = codeRepository
                .findFirstByBuyerEmailAndSelfFundedTrueOrderByCreatedAtDesc(email).orElse(null);
        if (code == null || code.getUsedByUser() == null) return silent;

        User account = userRepository.findById(code.getUsedByUser().getId())
                .filter(u -> u.getDeletedAt() == null).orElse(null);
        if (account == null) return silent;

        // The purchase landed on a full account — one with its own password or Google
        // sign-in. Handing that account a session for knowing an address would be a way
        // around its real credentials, so this points them at the front door instead. The
        // note only ever reaches the mailbox's owner, so it tells a prober nothing.
        if (!guestAccountService.isGuestAccount(account)) {
            emailSender.send(email, "Your HueVista rooms",
                    "You already have a full HueVista account, so your rooms are waiting behind "
                    + "your usual sign-in.\n\nSign in at the address you normally use — if you "
                    + "have forgotten your password, use the \"forgot password\" link there.\n\n"
                    + "If you didn't ask for this, you can ignore this email.");
            return silent;
        }

        // Inside the cooldown we stop silently rather than complaining. An error here
        // would answer "yes, this address has an account" to anyone who asked twice.
        KioskReentryCode last = reentryRepository
                .findTopByDestinationOrderByCreatedAtDesc(email).orElse(null);
        if (last != null
                && Duration.between(last.getCreatedAt(), LocalDateTime.now()).compareTo(COOLDOWN) < 0) {
            return silent;
        }

        // Only the newest code may work.
        List<KioskReentryCode> prior =
                reentryRepository.findByDestinationAndConsumedFalseOrderByCreatedAtDesc(email);
        prior.forEach(c -> c.setConsumed(true));
        reentryRepository.saveAll(prior);

        String plain = String.format("%06d", RANDOM.nextInt(1_000_000));
        reentryRepository.save(KioskReentryCode.builder()
                .userId(account.getId())
                .codeHash(passwordEncoder.encode(plain))
                .destination(email)
                .expiresAt(LocalDateTime.now().plus(TTL))
                .build());

        emailSender.send(email, "Your HueVista sign-in code",
                "Your sign-in code is " + plain + ".\n\nIt opens the room you bought at "
                + code.getOrganization().getName() + " and expires in " + TTL.toMinutes()
                + " minutes.\n\nIf you didn't ask for this, you can ignore this email — "
                + "nobody can get in without the code.");

        log.info("Kiosk re-entry code issued for account {} (code {})", account.getId(), code.getCode());
        return silent;
    }

    /** Exchange a correct code for a session on the account the purchase lives on. */
    @Transactional
    public AuthResponse confirm(String rawEmail, String codeInput) {
        String email = Emails.normalize(rawEmail);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Enter the email address you used at the shop.");
        }

        List<KioskReentryCode> live =
                reentryRepository.findByDestinationAndConsumedFalseOrderByCreatedAtDesc(email);
        if (live.isEmpty()) {
            throw new IllegalArgumentException("Request a code first.");
        }
        KioskReentryCode entry = live.get(0);

        if (entry.getExpiresAt().isBefore(LocalDateTime.now())) {
            entry.setConsumed(true);
            reentryRepository.save(entry);
            throw new IllegalArgumentException("That code has expired. Request a new one.");
        }
        if (entry.getAttempts() >= MAX_ATTEMPTS) {
            entry.setConsumed(true);
            reentryRepository.save(entry);
            throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
        }

        String supplied = codeInput == null ? "" : codeInput.trim();
        if (!passwordEncoder.matches(supplied, entry.getCodeHash())) {
            entry.setAttempts(entry.getAttempts() + 1);
            reentryRepository.save(entry);
            int left = Math.max(0, MAX_ATTEMPTS - entry.getAttempts());
            throw new IllegalArgumentException(
                    "Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left.");
        }

        entry.setConsumed(true);
        reentryRepository.save(entry);

        User account = userRepository.findById(entry.getUserId())
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalStateException(
                        "That kiosk account has since been merged into another one. "
                        + "Sign in with the account you moved it to."));

        auditService.record(account.getId(), "KIOSK_REENTRY", "USER", account.getId(),
                "signed in with an emailed code");
        log.info("Kiosk re-entry: account {} signed in by emailed code", account.getId());
        return authService.buildAuthResponse(account);
    }
}
