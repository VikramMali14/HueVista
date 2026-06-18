package com.gridstore.huevista.account.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

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
    // Serialized as an ISO-8601 instant with offset (…Z) so the browser parses an
    // unambiguous moment. A zoneless LocalDateTime was read in the frontend server's
    // timezone, skewing the guest cookie TTL when the two servers' zones differ.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;
    // Paint companies the shop unlocked for this guest. Empty = all brands; the
    // studio limits the guest's shade picker to these.
    private List<String> allowedBrands;
}
