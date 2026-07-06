package com.gridstore.huevista.auth.controller;

import com.gridstore.huevista.auth.dto.*;
import com.gridstore.huevista.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, login, token refresh and logout")
public class AuthController {

    private final AuthService authService;
    private final com.gridstore.huevista.auth.service.PasswordResetService passwordResetService;

    @Operation(summary = "Register a new user", description = "Creates a local account and returns JWT access + refresh tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered successfully"),
            @ApiResponse(responseCode = "409", description = "Email already in use")
    })
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Login", description = "Authenticate with email and password. Returns JWT access + refresh tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Refresh access token", description = "Exchange a valid refresh token for a new access + refresh token pair (token rotation).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens refreshed"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid or expired")
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request.getRefreshToken()));
    }

    @Operation(summary = "Exchange a one-time OAuth code for tokens",
            description = "The Google callback lands with a short-lived single-use code instead of tokens; "
                    + "this trades it for the real access + refresh pair. 401 when the code is invalid, "
                    + "expired or already used.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens issued"),
            @ApiResponse(responseCode = "401", description = "Code invalid, expired or already used")
    })
    @SecurityRequirements
    @PostMapping("/oauth2/exchange")
    public ResponseEntity<AuthResponse> exchangeOAuthCode(@Valid @RequestBody OAuthExchangeRequest request) {
        return ResponseEntity.ok(authService.exchangeOAuthCode(request.getCode()));
    }

    @Operation(summary = "Request a password reset", description = "Emails a 6-digit reset code if the account exists. Always 200 (no account enumeration).")
    @SecurityRequirements
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "If an account exists for that email, a reset code is on its way."));
    }

    @Operation(summary = "Reset password with a code", description = "Validates the emailed code and sets a new password, revoking existing sessions.")
    @SecurityRequirements
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Your password has been reset. Please sign in."));
    }

    @Operation(summary = "Request a password reset by SMS", description = "Texts a 6-digit reset code to a verified mobile number if one matches. Always 200 (no enumeration).")
    @SecurityRequirements
    @PostMapping("/forgot-password/phone")
    public ResponseEntity<Map<String, String>> forgotPasswordByPhone(@Valid @RequestBody ForgotPasswordPhoneRequest request) {
        passwordResetService.requestResetByPhone(request.getPhone());
        return ResponseEntity.ok(Map.of("message", "If a verified mobile matches, a reset code is on its way."));
    }

    @Operation(summary = "Reset password with an SMS code", description = "Validates the texted code for the verified mobile and sets a new password, revoking existing sessions.")
    @SecurityRequirements
    @PostMapping("/reset-password/phone")
    public ResponseEntity<Map<String, String>> resetPasswordByPhone(@Valid @RequestBody ResetPasswordPhoneRequest request) {
        passwordResetService.resetPasswordByPhone(request.getPhone(), request.getCode(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Your password has been reset. Please sign in."));
    }

    @Operation(summary = "Logout", description = "Revokes all refresh tokens for the authenticated user.")
    @ApiResponse(responseCode = "200", description = "Logged out successfully")
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@AuthenticationPrincipal UserDetails userDetails) {
        authService.logout(userDetails.getUsername());
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @Operation(summary = "Delete account", description = "Permanently deletes the authenticated user's account: scrubs personal data and revokes all sessions.")
    @ApiResponse(responseCode = "204", description = "Account deleted")
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal UserDetails userDetails) {
        authService.deleteAccount(userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get current user", description = "Returns the authenticated user's ID. Useful for verifying a token is still valid.")
    @ApiResponse(responseCode = "200", description = "User info returned")
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(Map.of("userId", userDetails.getUsername()));
    }

    @Operation(summary = "Get full profile", description = "Returns full profile of the authenticated user.")
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(authService.getProfile(userDetails.getUsername()));
    }

    @Operation(summary = "Update profile", description = "Update name and/or picture URL.")
    @PatchMapping("/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(authService.updateProfile(userDetails.getUsername(), request));
    }

    @Operation(summary = "Change password", description = "Change password for local accounts. Revokes all existing sessions.")
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userDetails.getUsername(), request);
        return ResponseEntity.ok(Map.of("message", "Password changed. Please log in again."));
    }
}
