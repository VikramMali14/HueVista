package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.dto.*;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.RefreshToken;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import com.gridstore.huevista.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final com.gridstore.huevista.account.service.AccountService accountService;
    private final com.gridstore.huevista.billing.service.BillingService billingService;

    private static final int TRIAL_DAYS = 14;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       @Lazy AuthenticationManager authenticationManager,
                       com.gridstore.huevista.account.service.AccountService accountService,
                       com.gridstore.huevista.billing.service.BillingService billingService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.accountService = accountService;
        this.billingService = billingService;
    }

    @Value("${app.refresh-token.expiration-ms}")
    private long refreshTokenExpirationMs;

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final java.time.Duration LOGIN_LOCK = java.time.Duration.ofMinutes(15);

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Email already in use: " + request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .phoneNumber(blankToNull(request.getPhone()))
                .build();

        userRepository.save(user);
        log.info("Registered new user: {}", user.getEmail());

        // Retailer trial signup: provision a shop org + a free trial subscription so AI
        // features work immediately. Best-effort — a provisioning hiccup must not fail
        // the registration itself (the user can still sign in).
        if (request.getShopName() != null && !request.getShopName().isBlank()) {
            try {
                accountService.provisionRetailerOrg(user.getId(), request.getShopName(), request.getCity(), request.getState());
                billingService.grantTrial(user.getId(), planFromTier(request.getTier()), TRIAL_DAYS);
            } catch (Exception e) {
                log.warn("Trial provisioning failed for {}: {}", user.getEmail(), e.getMessage());
            }
        }
        return buildAuthResponse(user);
    }

    private static com.gridstore.huevista.billing.model.Plan planFromTier(String tier) {
        if (tier == null) return com.gridstore.huevista.billing.model.Plan.PROFESSIONAL;
        return switch (tier.trim().toLowerCase()) {
            case "starter" -> com.gridstore.huevista.billing.model.Plan.STARTER;
            case "business" -> com.gridstore.huevista.billing.model.Plan.BUSINESS;
            default -> com.gridstore.huevista.billing.model.Plan.PROFESSIONAL; // "pro"/"professional"/blank
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // noRollbackFor: a failed login throws to return 401/429, but we MUST still
    // commit the incremented failedLoginAttempts (otherwise the lockout counter
    // is rolled back and brute-force protection never trips).
    @Transactional(noRollbackFor = {
            org.springframework.security.authentication.BadCredentialsException.class,
            org.springframework.security.authentication.LockedException.class,
    })
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // Reject early if the account is currently locked.
        if (user != null && user.getLockedUntil() != null
                && user.getLockedUntil().isAfter(java.time.LocalDateTime.now())) {
            long mins = java.time.Duration.between(java.time.LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1;
            throw new org.springframework.security.authentication.LockedException(
                    "Too many failed attempts. Try again in about " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
        }

        try {
            // AuthenticationManager delegates to DaoAuthenticationProvider →
            // UserDetailsService → BCrypt comparison. Throws on bad credentials.
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            if (user != null) {
                int attempts = user.getFailedLoginAttempts() + 1;
                if (attempts >= MAX_LOGIN_ATTEMPTS) {
                    user.setLockedUntil(java.time.LocalDateTime.now().plus(LOGIN_LOCK));
                    user.setFailedLoginAttempts(0);
                    log.warn("Account locked after {} failed logins: {}", MAX_LOGIN_ATTEMPTS, user.getEmail());
                } else {
                    user.setFailedLoginAttempts(attempts);
                }
                userRepository.save(user);
            }
            throw e;
        }

        // Success — clear any failed-attempt state.
        if (user != null && (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null)) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
        User authed = user != null ? user
                : userRepository.findByEmail(request.getEmail())
                        .orElseThrow(() -> new IllegalStateException("User not found after authentication"));

        log.info("User logged in: {}", authed.getEmail());
        return buildAuthResponse(authed);
    }

    @Transactional
    public AuthResponse refreshToken(String rawToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new IllegalArgumentException("Refresh token not found"));

        if (stored.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(stored);
            throw new IllegalArgumentException("Refresh token expired — please log in again");
        }

        // Rotate: invalidate the old token, issue a fresh pair
        refreshTokenRepository.delete(stored);
        return buildAuthResponse(stored.getUser());
    }

    @Transactional
    public void logout(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        refreshTokenRepository.deleteByUser(user);
        log.info("User logged out, refresh tokens revoked: {}", user.getEmail());
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return UserProfileResponse.from(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setName(request.getName());
        if (request.getPicture() != null) {
            user.setPicture(request.getPicture());
        }
        userRepository.save(user);
        log.info("Profile updated: {}", user.getEmail());
        return UserProfileResponse.from(user);
    }

    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getPassword() == null) {
            throw new IllegalStateException("OAuth2 accounts cannot set a password here");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        refreshTokenRepository.deleteByUser(user);
        log.info("Password changed, all sessions revoked: {}", user.getEmail());
    }

    /**
     * Central method: generates a fresh access + refresh token pair for any user.
     * Called by register, login, OAuth2 success handler, and token refresh.
     */
    @Transactional
    public AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail());

        String rawRefresh = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .token(rawRefresh)
                .user(user)
                .expiryDate(Instant.now().plusMillis(refreshTokenExpirationMs))
                .build();
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationMs() / 1000)
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .name(user.getName())
                        .email(user.getEmail())
                        .picture(user.getPicture())
                        .provider(user.getProvider().name())
                        .role(user.getRole().name())
                        .build())
                .build();
    }
}
