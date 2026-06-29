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
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final com.gridstore.huevista.notification.EmailSender emailSender;

    private static final int TRIAL_DAYS = 14;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       @Lazy AuthenticationManager authenticationManager,
                       com.gridstore.huevista.account.service.AccountService accountService,
                       com.gridstore.huevista.billing.service.BillingService billingService,
                       com.gridstore.huevista.common.audit.AuditService auditService,
                       com.gridstore.huevista.notification.EmailSender emailSender) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.accountService = accountService;
        this.billingService = billingService;
        this.auditService = auditService;
        this.emailSender = emailSender;
    }

    @Value("${app.refresh-token.expiration-ms}")
    private long refreshTokenExpirationMs;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final java.time.Duration LOGIN_LOCK = java.time.Duration.ofMinutes(15);

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Email already in use: " + email);
        }

        // Public signup ALWAYS creates a CUSTOMER. Shops/retailers are provisioned by an
        // admin only (see adminCreateRetailer), so the public path can never make a RETAILER
        // or self-provision a shop org/trial.
        User user = User.builder()
                .name(request.getName())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .phoneNumber(blankToNull(request.getPhone()))
                .role(com.gridstore.huevista.auth.model.UserRole.CUSTOMER)
                .build();

        userRepository.save(user);
        log.info("Registered new CUSTOMER: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    /**
     * ADMIN-only: create a RETAILER (shop) account with a provisioned org + free trial.
     * Atomic — if org/trial provisioning fails, the whole creation rolls back.
     */
    @Transactional
    public AdminUserResponse adminCreateRetailer(CreateRetailerRequest request) {
        String email = com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Email already in use: " + email);
        }
        User user = User.builder()
                .name(request.getName())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true) // admin-vetted
                .phoneNumber(blankToNull(request.getPhone()))
                .role(com.gridstore.huevista.auth.model.UserRole.RETAILER)
                .build();
        userRepository.save(user);
        accountService.provisionRetailerOrg(user.getId(), request.getShopName(), request.getCity(), request.getState());
        billingService.grantTrial(user.getId(), planFromTier(request.getTier()), TRIAL_DAYS);
        sendShopWelcomeEmail(user, request);
        log.info("Admin created RETAILER {} (shop: {})", user.getEmail(), request.getShopName());
        return AdminUserResponse.from(user);
    }

    /** Best-effort welcome email with the new shop's login — never fails creation. */
    private void sendShopWelcomeEmail(User user, CreateRetailerRequest request) {
        try {
            String url = firstFrontendOrigin();
            emailSender.send(user.getEmail(),
                    "Your HueVista shop account is ready",
                    "Hi " + request.getName() + ",\n\n"
                            + "Your HueVista shop account for \"" + request.getShopName() + "\" is ready.\n\n"
                            + "Sign in:  " + url + "/sign-in\n"
                            + "Email:    " + user.getEmail() + "\n"
                            + "Password: " + request.getPassword() + "\n\n"
                            + "Please change your password after signing in (or use \"Forgot password\" to set your own).\n\n"
                            + "— HueVista");
        } catch (Exception e) {
            log.warn("Welcome email to {} failed: {}", user.getEmail(), e.getMessage());
        }
    }

    /** The first configured CORS origin is the frontend base URL; fall back to local dev. */
    private String firstFrontendOrigin() {
        if (allowedOrigins != null) {
            for (String o : allowedOrigins.split(",")) {
                String t = o.trim();
                if (!t.isEmpty() && !"*".equals(t)) {
                    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
                }
            }
        }
        return "http://localhost:3000";
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
        String email = com.gridstore.huevista.auth.util.Emails.normalize(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);

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
                    new UsernamePasswordAuthenticationToken(email, request.getPassword())
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
                : userRepository.findByEmail(email)
                        .orElseThrow(() -> new IllegalStateException("User not found after authentication"));

        log.info("User logged in: {}", authed.getEmail());
        return buildAuthResponse(authed);
    }

    @Transactional
    public AuthResponse refreshToken(String rawToken) {
        // 401 (not 400) for refresh-token problems: an expired/invalid refresh token is
        // an auth failure, matching the endpoint's documented contract and the client's
        // "session expired -> re-login" handling.
        RefreshToken stored = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Refresh token invalid — please log in again."));

        // Capture the owner while the entity is still managed (lazy proxy resolves
        // within this transaction) so we can build the response after the row is gone.
        User user = stored.getUser();
        boolean expired = stored.getExpiryDate().isBefore(Instant.now());

        // Rotate atomically. When an access token expires, the client commonly fires
        // several API calls at once and each retries through /auth/refresh with the SAME
        // refresh token. With an entity delete, every racing request loads the row and
        // calls delete(), so the loser commits a delete of an already-gone row and blows
        // up with StaleObjectStateException -> 500. A bulk delete-by-id returns the row
        // count instead: exactly one request removes the row and proceeds; the rest get 0.
        int deleted = refreshTokenRepository.deleteByIdReturningCount(stored.getId());

        if (expired) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Refresh token expired — please log in again.");
        }
        if (deleted == 0) {
            // Lost the rotation race: a concurrent request already consumed this token.
            // Treat as an auth failure rather than minting a second pair for one rotation.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Refresh token already used — please log in again.");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        refreshTokenRepository.deleteByUser(user);
        auditService.record(userId, "LOGOUT", "USER", userId, "all refresh tokens revoked");
        log.info("User logged out, refresh tokens revoked: {}", user.getEmail());
    }

    /**
     * Soft-deletes the authenticated user's account: revokes all sessions and scrubs
     * personal data, keeping the row (projects/images/orgs reference it via FK) but
     * tombstoning it. The original email is freed so the person can re-register, and
     * the account becomes unusable (no one can log in as a tombstoned email).
     */
    @Transactional
    public void deleteAccount(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        refreshTokenRepository.deleteByUser(user);
        user.setEmail("deleted-" + user.getId() + "@deleted.huevista.invalid");
        user.setName("Deleted user");
        user.setPassword(null);
        user.setPicture(null);
        user.setProviderId(null);
        user.setPhoneNumber(null);
        user.setPhoneVerified(false);
        user.setEmailVerified(false);
        user.setDeletedAt(java.time.LocalDateTime.now());
        userRepository.save(user);
        auditService.record(userId, "ACCOUNT_DELETED", "USER", userId,
                "account soft-deleted: PII scrubbed, sessions revoked");
        log.info("Account soft-deleted: {}", userId);
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
        auditService.record(userId, "PASSWORD_CHANGE", "USER", userId, "all sessions revoked");
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
