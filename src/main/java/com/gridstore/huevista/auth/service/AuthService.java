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

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       @Lazy AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @Value("${app.refresh-token.expiration-ms}")
    private long refreshTokenExpirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use: " + request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .build();

        userRepository.save(user);
        log.info("Registered new user: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // AuthenticationManager delegates to DaoAuthenticationProvider →
        // UserDetailsService → BCrypt comparison. Throws on bad credentials.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalStateException("User not found after authentication"));

        log.info("User logged in: {}", user.getEmail());
        return buildAuthResponse(user);
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
