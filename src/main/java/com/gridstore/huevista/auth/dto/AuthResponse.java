package com.gridstore.huevista.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;

    @Builder.Default
    private String tokenType = "Bearer";

    private long expiresIn; // seconds

    private UserInfo user;

    /**
     * True when the password was correct but a second factor is still required
     * (ADMIN accounts with mail delivery configured). Tokens are null; the client
     * must resubmit with the emailed code via POST /api/auth/login/otp.
     */
    private Boolean twoFactorRequired;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserInfo {
        private String id;
        private String name;
        private String email;
        private String picture;
        private String provider;
        private String role;
    }
}
