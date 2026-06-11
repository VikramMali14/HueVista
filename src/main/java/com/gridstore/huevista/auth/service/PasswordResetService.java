package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.model.PasswordResetCode;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.PasswordResetCodeRepository;
import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.notification.EmailSender;
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
 * Forgot-password flow via a 6-digit emailed code. Mirrors {@link VerificationService}:
 * codes are BCrypt-hashed, single-use, expire after {@link #TTL}, are rate-limited
 * by {@link #COOLDOWN}, and lock out after {@link #MAX_ATTEMPTS} bad tries. To avoid
 * account enumeration, requesting a reset for an unknown email is a silent no-op.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration COOLDOWN = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    /** Send a reset code. Silent (no exception) if the email isn't registered. */
    @Transactional
    public void requestReset(String email) {
        User user = userRepository.findByEmail(email.trim().toLowerCase()).orElse(null);
        if (user == null) {
            log.info("Password reset requested for unknown email (silently ignored)");
            return;
        }
        if (user.getPassword() == null) {
            // OAuth-only account — no local password to reset.
            log.info("Password reset requested for an OAuth-only account: {}", user.getEmail());
            return;
        }

        var last = codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId());
        if (last.isPresent()
                && Duration.between(last.get().getCreatedAt(), LocalDateTime.now()).getSeconds() < COOLDOWN.getSeconds()) {
            // Within cooldown — silently skip re-sending (don't reveal timing).
            log.info("Password reset resend within cooldown; skipping for {}", user.getEmail());
            return;
        }

        List<PasswordResetCode> prior = codeRepository.findByUserIdAndConsumedFalse(user.getId());
        prior.forEach(c -> c.setConsumed(true));
        codeRepository.saveAll(prior);

        String code = String.format("%06d", random.nextInt(1_000_000));
        codeRepository.save(PasswordResetCode.builder()
                .userId(user.getId())
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(LocalDateTime.now().plus(TTL))
                .attempts(0)
                .consumed(false)
                .build());

        emailSender.send(user.getEmail(),
                "Reset your HueVista password",
                "Your HueVista password reset code is " + code + ".\n\nIt expires in " + TTL.toMinutes()
                        + " minutes. If you didn't request this, you can ignore this email.");
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void resetPassword(String email, String codeInput, String newPassword) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Incorrect or expired code."));
        List<PasswordResetCode> active = codeRepository.findActiveForUpdate(user.getId());
        if (active.isEmpty()) {
            throw new IllegalArgumentException("Request a reset code first.");
        }
        PasswordResetCode rc = active.get(0);

        if (rc.getExpiresAt().isBefore(LocalDateTime.now())) {
            rc.setConsumed(true);
            codeRepository.save(rc);
            throw new IllegalArgumentException("That code has expired. Request a new one.");
        }
        if (rc.getAttempts() >= MAX_ATTEMPTS) {
            rc.setConsumed(true);
            codeRepository.save(rc);
            throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
        }
        if (!passwordEncoder.matches(codeInput == null ? "" : codeInput.trim(), rc.getCodeHash())) {
            rc.setAttempts(rc.getAttempts() + 1);
            codeRepository.save(rc);
            int left = Math.max(0, MAX_ATTEMPTS - rc.getAttempts());
            throw new IllegalArgumentException("Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left.");
        }

        rc.setConsumed(true);
        codeRepository.save(rc);
        user.setPassword(passwordEncoder.encode(newPassword));
        // Reset any login lockout and revoke all existing sessions.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        refreshTokenRepository.deleteByUser(user);
        auditService.record(user.getId(), "PASSWORD_RESET", "USER", user.getId(),
                "via emailed reset code; all sessions revoked");
        log.info("Password reset for {}", user.getEmail());
    }
}
