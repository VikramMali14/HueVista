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
    private final com.gridstore.huevista.auth.repository.OAuthExchangeCodeRepository oauthExchangeCodeRepository;
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
                       com.gridstore.huevista.auth.repository.OAuthExchangeCodeRepository oauthExchangeCodeRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       @Lazy AuthenticationManager authenticationManager,
                       com.gridstore.huevista.account.service.AccountService accountService,
                       com.gridstore.huevista.billing.service.BillingService billingService,
                       com.gridstore.huevista.common.audit.AuditService auditService,
                       com.gridstore.huevista.notification.EmailSender emailSender) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.oauthExchangeCodeRepository = oauthExchangeCodeRepository;
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

    /**
     * How long after rotation a just-consumed refresh token is still honoured.
     * When an access token expires, a browser with several tabs (or several
     * parallel fetches) fires multiple /auth/refresh calls with the SAME token;
     * without a grace window the losers would 401 and the client would clear the
     * session — an intermittent, racy logout. Reuse AFTER this window is treated
     * as theft and revokes every session for the user.
     */
    @Value("${app.refresh-token.reuse-grace-ms:60000}")
    private long refreshReuseGraceMs;

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

    /**
     * Best-effort welcome email with the new shop's login — never fails creation.
     * Deliberately does NOT include the initial password: email is plaintext at
     * rest with most providers, so the credential would outlive its purpose in an
     * inbox forever. The admin hands the password over out-of-band (they set it),
     * and the mail points at "Forgot password" for the owner to mint their own.
     */
    private void sendShopWelcomeEmail(User user, CreateRetailerRequest request) {
        try {
            String url = firstFrontendOrigin();
            emailSender.send(user.getEmail(),
                    "Your HueVista shop account is ready",
                    "Hi " + request.getName() + ",\n\n"
                            + "Your HueVista shop account for \"" + request.getShopName() + "\" is ready.\n\n"
                            + "Sign in:  " + url + "/sign-in\n"
                            + "Email:    " + user.getEmail() + "\n\n"
                            + "Your initial password comes from the person who set up your account. "
                            + "Prefer your own? Use \"Forgot password\" on the sign-in page to set one:\n"
                            + url + "/sign-in/forgot\n\n"
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
        // "session expired -> re-login" handling. Tokens are stored hashed, so the
        // lookup is by the SHA-256 of the presented value.
        String tokenHash = com.gridstore.huevista.auth.util.TokenHasher.sha256Hex(rawToken);
        RefreshToken stored = refreshTokenRepository.findByToken(tokenHash)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Refresh token invalid — please log in again."));

        // Capture the owner while the entity is managed (lazy proxy resolves within
        // this transaction) so we can build the response after the row is consumed.
        User user = stored.getUser();
        Instant now = Instant.now();

        if (stored.getExpiryDate().isBefore(now)) {
            refreshTokenRepository.deleteByIdReturningCount(stored.getId());
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Refresh token expired — please log in again.");
        }

        // Rotate atomically: exactly one racing request flips usedAt from null and
        // "wins". Losers fall through to the grace-window check below instead of
        // failing — when an access token expires, a browser fires several parallel
        // requests that each retry through /auth/refresh with the SAME token, and
        // hard-failing the losers used to clear the session cookies (random logout).
        if (stored.getUsedAt() == null) {
            int consumed = refreshTokenRepository.markUsedReturningCount(stored.getId(), now);
            if (consumed == 1) {
                return buildAuthResponse(user);
            }
            // Lost the race. The row was either just consumed by a parallel refresh
            // (honour it — it happened milliseconds ago, well within grace) or deleted
            // by a concurrent logout/revocation (then the session must NOT come back).
            if (!refreshTokenRepository.existsById(stored.getId())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Refresh token revoked — please log in again.");
            }
            return buildAuthResponse(user);
        }

        // Token already consumed by an earlier rotation.
        if (stored.getUsedAt().isAfter(now.minusMillis(refreshReuseGraceMs))) {
            // Within the grace window: a parallel tab/request replaying the same
            // rotation. Mint a fresh pair instead of logging the user out.
            return buildAuthResponse(user);
        }

        // Reuse long after rotation — the token was very likely captured/replayed.
        // Standard rotation theft response: revoke every session for this user.
        refreshTokenRepository.deleteByUser(user);
        auditService.record(user.getId(), "REFRESH_TOKEN_REUSE", "USER", user.getId(),
                "rotated refresh token replayed after grace window — all sessions revoked");
        log.warn("Rotated refresh token replayed after grace window; sessions revoked for user {}", user.getId());
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Refresh token already used — please log in again.");
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
     * Mints the one-time code the OAuth2 success handler redirects with, INSTEAD of
     * the real tokens. A URL fragment never reaches a server, but it is readable by
     * browser extensions and lingers in history — and the refresh token lives for
     * days. This code is 256-bit random, stored SHA-256-hashed, single-use and dead
     * in sixty seconds, so anything scraped from the URL later is worthless. Any
     * previous codes for the user are dropped (only the newest hop can win).
     */
    @Transactional
    public String createOAuthExchangeCode(User user) {
        oauthExchangeCodeRepository.deleteByUserId(user.getId());
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String raw = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        oauthExchangeCodeRepository.save(com.gridstore.huevista.auth.model.OAuthExchangeCode.builder()
                .userId(user.getId())
                .codeHash(com.gridstore.huevista.auth.util.TokenHasher.sha256Hex(raw))
                .expiresAt(java.time.LocalDateTime.now().plusSeconds(60))
                .build());
        return raw;
    }

    /**
     * Trades a one-time OAuth exchange code for the real token pair. Single-use and
     * expiry are enforced in one atomic UPDATE, so a replayed (or raced) exchange
     * matches zero rows and gets 401 — same contract as refresh-token problems.
     */
    @Transactional
    public AuthResponse exchangeOAuthCode(String rawCode) {
        String hash = com.gridstore.huevista.auth.util.TokenHasher.sha256Hex(
                rawCode == null ? "" : rawCode.trim());
        if (oauthExchangeCodeRepository.consume(hash, java.time.LocalDateTime.now()) == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "This sign-in link is invalid or has expired — please sign in again.");
        }
        var code = oauthExchangeCodeRepository.findByCodeHash(hash)
                .orElseThrow(() -> new IllegalStateException("Consumed OAuth code row vanished"));
        User user = userRepository.findById(code.getUserId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "This sign-in link is invalid or has expired — please sign in again."));
        return buildAuthResponse(user);
    }

    /**
     * Central method: generates a fresh access + refresh token pair for any user.
     * Called by register, login, OAuth2 success handler, and token refresh.
     */
    @Transactional
    public AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail());

        // Only the SHA-256 of the token is persisted; the raw value goes to the
        // client once in the response and cannot be recovered from the database.
        String rawRefresh = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .token(com.gridstore.huevista.auth.util.TokenHasher.sha256Hex(rawRefresh))
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
