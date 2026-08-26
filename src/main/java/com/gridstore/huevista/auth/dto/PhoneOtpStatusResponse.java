package com.gridstore.huevista.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What the client needs after a code has been sent — and nothing more. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PhoneOtpStatusResponse {

    /**
     * The number with all but its last few digits starred out.
     *
     * <p>Echoed back masked rather than in full so the screen can say "sent to
     * *******210" and confirm the right number was used, without a public endpoint
     * repeating a mobile number to whoever asked.
     */
    private String destination;

    private int expiresInSeconds;
    private int cooldownSeconds;
}
