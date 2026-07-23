package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.auth.dto.AuthResponse;
import lombok.Builder;
import lombok.Data;

/**
 * Returned when a walk-in redeems a retailer access code. Carries a full auth
 * session (access + refresh tokens) for the auto-provisioned CUSTOMER account so
 * the client can sign the customer straight in, plus the shop context shown on the
 * success screen. No account or login is required beforehand.
 */
@Data
@Builder
public class RedeemAccountResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresIn; // seconds
    private AuthResponse.UserInfo user;

    // Shop context for the success screen.
    private String shopName;
    private int validDays;
    private String customerName;
}
