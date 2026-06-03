package com.gridstore.huevista.auth.dto;

import lombok.Builder;
import lombok.Data;

/** Returned after a code is sent, so the UI can show where it went and timers. */
@Data
@Builder
public class VerificationStatusResponse {
    private String channel;          // EMAIL | PHONE
    private String destination;      // masked, e.g. "j***@gmail.com" or "******321"
    private int expiresInSeconds;    // code lifetime
    private int cooldownSeconds;     // wait before a resend is allowed
}
