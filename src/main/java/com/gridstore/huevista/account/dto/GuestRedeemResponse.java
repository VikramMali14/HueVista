package com.gridstore.huevista.account.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Returned when an anonymous guest redeems a shop access code (no account).
 * Carries the guest token the client stores to act as the code's owner, plus the
 * code itself (shown back to the guest as their "pickup code" for the shop) and
 * the window it's valid for.
 */
@Data
@Builder
public class GuestRedeemResponse {
    private String guestToken;
    private String code;
    private String shopName;
    private int validDays;
    private LocalDateTime expiresAt;
}
