package com.gridstore.huevista.auth.controller;

import com.gridstore.huevista.auth.dto.ConfirmCodeRequest;
import com.gridstore.huevista.auth.dto.SendPhoneCodeRequest;
import com.gridstore.huevista.auth.dto.UserProfileResponse;
import com.gridstore.huevista.auth.dto.VerificationStatusResponse;
import com.gridstore.huevista.auth.model.VerificationChannel;
import com.gridstore.huevista.auth.service.VerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Email + mobile verification via 6-digit one-time codes. All endpoints require
 * an authenticated user (verification is post-login and non-blocking). The
 * username on the principal is the user id.
 */
@RestController
@RequestMapping("/api/auth/verify")
@RequiredArgsConstructor
@Tag(name = "Verification", description = "Email and mobile verification via one-time codes")
public class VerificationController {

    private final VerificationService verificationService;

    @Operation(summary = "Send an email verification code")
    @ApiResponse(responseCode = "200", description = "Code sent (see masked destination)")
    @PostMapping("/email/send")
    public ResponseEntity<VerificationStatusResponse> sendEmail(@AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(verificationService.sendEmailCode(user.getUsername()));
    }

    @Operation(summary = "Confirm an email verification code")
    @ApiResponse(responseCode = "200", description = "Updated profile with emailVerified = true")
    @ApiResponse(responseCode = "400", description = "Incorrect or expired code")
    @PostMapping("/email/confirm")
    public ResponseEntity<UserProfileResponse> confirmEmail(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody ConfirmCodeRequest request) {
        return ResponseEntity.ok(verificationService.confirm(user.getUsername(), VerificationChannel.EMAIL, request.getCode()));
    }

    @Operation(summary = "Send a mobile verification code", description = "Supply the number to verify; it's saved (unverified) and an SMS code is sent.")
    @ApiResponse(responseCode = "200", description = "Code sent (see masked destination)")
    @PostMapping("/phone/send")
    public ResponseEntity<VerificationStatusResponse> sendPhone(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody(required = false) SendPhoneCodeRequest request) {
        String phone = request != null ? request.getPhoneNumber() : null;
        return ResponseEntity.ok(verificationService.sendPhoneCode(user.getUsername(), phone));
    }

    @Operation(summary = "Confirm a mobile verification code")
    @ApiResponse(responseCode = "200", description = "Updated profile with phoneVerified = true")
    @ApiResponse(responseCode = "400", description = "Incorrect or expired code")
    @PostMapping("/phone/confirm")
    public ResponseEntity<UserProfileResponse> confirmPhone(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody ConfirmCodeRequest request) {
        return ResponseEntity.ok(verificationService.confirm(user.getUsername(), VerificationChannel.PHONE, request.getCode()));
    }
}
