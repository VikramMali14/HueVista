package com.gridstore.huevista.store.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Returned when a kiosk payment verifies: the code the customer keeps (their
 * pickup code — the SHOP redeems the colours from it later, exactly like the
 * counter-issued guest codes) plus a guest token so the studio opens
 * immediately without typing anything.
 */
@Data
@Builder
public class StoreCheckoutResponse {
    private String guestToken;
    private String code;
    private String shopName;
    private int validDays;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;
    /** What was paid, for the on-screen receipt. */
    private int amountPaise;
}
