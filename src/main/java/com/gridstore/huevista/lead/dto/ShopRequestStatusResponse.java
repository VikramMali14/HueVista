package com.gridstore.huevista.lead.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What the public form gets back after submitting, or asking for a fresh code:
 * the request id to verify against and the throttles the UI counts down. The
 * address is masked — the browser already knows it, and echoing it in full turns
 * a request id into a way to read somebody's address back out.
 */
@Data
@Builder
public class ShopRequestStatusResponse {

    private String requestId;

    /** e.g. {@code p***@mehtapaints.in} — enough to confirm which inbox to open. */
    private String email;

    /** Seconds the emailed code stays good for. */
    private int expiresInSeconds;

    /** Seconds before another code may be requested. */
    private int cooldownSeconds;

    /** {@code PENDING_EMAIL} until the code is confirmed, then {@code AWAITING_APPROVAL}. */
    private String status;
}
