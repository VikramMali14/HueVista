package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.dto.UserProfileResponse;
import com.gridstore.huevista.auth.dto.VerificationStatusResponse;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.VerificationChannel;
import com.gridstore.huevista.auth.model.VerificationCode;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.repository.VerificationCodeRepository;
import com.gridstore.huevista.notification.EmailSender;
import com.gridstore.huevista.notification.SmsSender;
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
 * Issues and checks 6-digit email/phone verification codes. Codes are single-use,
 * BCrypt-hashed, expire after {@link #TTL}, are rate-limited by {@link #COOLDOWN},
 * and lock out after {@link #MAX_ATTEMPTS} bad tries. Verification is non-blocking:
 * users can still use the app while unverified — this just flips the verified flags.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final VerificationCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public VerificationStatusResponse sendEmailCode(String userId) {
        User user = getUser(userId);
        if (user.isEmailVerified()) {
            throw new IllegalStateException("Your email is already verified.");
        }
        return issue(user, VerificationChannel.EMAIL, user.getEmail());
    }

    @Transactional
    public VerificationStatusResponse sendPhoneCode(String userId, String phoneRaw) {
        User user = getUser(userId);
        String phone = normalizePhone(phoneRaw != null ? phoneRaw : user.getPhoneNumber());
        if (phone == null) {
            throw new IllegalArgumentException("Enter a mobile number to verify.");
        }
        // Persist the (unverified) number so resend works and the UI can show it.
        if (!phone.equals(user.getPhoneNumber())) {
            user.setPhoneNumber(phone);
            user.setPhoneVerified(false);
            userRepository.save(user);
        } else if (user.isPhoneVerified()) {
            throw new IllegalStateException("This mobile number is already verified.");
        }
        return issue(user, VerificationChannel.PHONE, phone);
    }

    // noRollbackFor: a wrong code throws IllegalArgumentException to return 400, but
    // we MUST still commit the attempts++ (otherwise the lockout counter is rolled
    // back and brute-force protection never trips). The pessimistic row lock from
    // findActiveForUpdate serialises concurrent confirms so the increment/consume
    // is atomic.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public UserProfileResponse confirm(String userId, VerificationChannel channel, String codeInput) {
        User user = getUser(userId);
        List<VerificationCode> active = codeRepository.findActiveForUpdate(userId, channel);
        if (active.isEmpty()) {
            throw new IllegalArgumentException("Request a code first.");
        }
        VerificationCode vc = active.get(0);

        if (vc.getExpiresAt().isBefore(LocalDateTime.now())) {
            vc.setConsumed(true);
            codeRepository.save(vc);
            throw new IllegalArgumentException("That code has expired. Request a new one.");
        }
        if (vc.getAttempts() >= MAX_ATTEMPTS) {
            vc.setConsumed(true);
            codeRepository.save(vc);
            throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
        }

        String code = codeInput == null ? "" : codeInput.trim();
        if (!passwordEncoder.matches(code, vc.getCodeHash())) {
            vc.setAttempts(vc.getAttempts() + 1);
            codeRepository.save(vc);
            int left = Math.max(0, MAX_ATTEMPTS - vc.getAttempts());
            throw new IllegalArgumentException("Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left.");
        }

        vc.setConsumed(true);
        codeRepository.save(vc);
        if (channel == VerificationChannel.EMAIL) {
            user.setEmailVerified(true);
        } else {
            user.setPhoneVerified(true);
        }
        userRepository.save(user);
        auditService.record(userId, channel + "_VERIFIED", "USER", userId, null);
        log.info("{} verified for user {}", channel, userId);
        return UserProfileResponse.from(user);
    }

    private VerificationStatusResponse issue(User user, VerificationChannel channel, String destination) {
        // Rate-limit: enforce a cooldown since the last code on this channel. Uses the
        // most recent code REGARDLESS of consumed status so verifying/expiring a code
        // can't reset the throttle (prevents rapid resend / SMS flooding).
        codeRepository.findTopByUserIdAndChannelOrderByCreatedAtDesc(user.getId(), channel)
                .ifPresent(last -> {
                    long since = Duration.between(last.getCreatedAt(), LocalDateTime.now()).getSeconds();
                    if (since < COOLDOWN.getSeconds()) {
                        throw new IllegalStateException(
                                "Please wait " + (COOLDOWN.getSeconds() - since) + "s before requesting another code.");
                    }
                });

        // Invalidate any prior un-consumed codes so only the newest works.
        List<VerificationCode> prior = codeRepository.findByUserIdAndChannelAndConsumedFalse(user.getId(), channel);
        prior.forEach(c -> c.setConsumed(true));
        codeRepository.saveAll(prior);

        String code = String.format("%06d", random.nextInt(1_000_000));
        codeRepository.save(VerificationCode.builder()
                .userId(user.getId())
                .channel(channel)
                .codeHash(passwordEncoder.encode(code))
                .destination(destination)
                .expiresAt(LocalDateTime.now().plus(TTL))
                .attempts(0)
                .consumed(false)
                .build());

        long minutes = TTL.toMinutes();
        if (channel == VerificationChannel.EMAIL) {
            emailSender.send(destination,
                    "Your HueVista verification code",
                    "Your HueVista verification code is " + code + ".\n\nIt expires in " + minutes
                            + " minutes. If you didn't request this, you can ignore this email.");
        } else {
            smsSender.send(destination, "HueVista code: " + code + " (valid " + minutes + " min)");
        }

        return VerificationStatusResponse.builder()
                .channel(channel.name())
                .destination(mask(channel, destination))
                .expiresInSeconds((int) TTL.getSeconds())
                .cooldownSeconds((int) COOLDOWN.getSeconds())
                .build();
    }

    private User getUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    /** Keep an optional leading +, strip separators, require 8–15 digits. */
    private String normalizePhone(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("[\\s\\-()]", "");
        if (cleaned.isEmpty()) return null;
        if (!cleaned.matches("^\\+?[0-9]{8,15}$")) {
            throw new IllegalArgumentException("Enter a valid mobile number with country code, e.g. +9198…");
        }
        return cleaned;
    }

    private String mask(VerificationChannel channel, String destination) {
        if (channel == VerificationChannel.EMAIL) {
            int at = destination.indexOf('@');
            if (at <= 1) return destination;
            return destination.charAt(0) + "***" + destination.substring(at);
        }
        int keep = Math.min(3, destination.length());
        return "*".repeat(Math.max(0, destination.length() - keep)) + destination.substring(destination.length() - keep);
    }
}
