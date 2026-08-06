package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * What the browser tells us about a checkout it was showing.
 *
 * <p>Only the things the server genuinely cannot know: whether the buyer ever saw the
 * window, whether they closed it, and what Razorpay said when it refused the card. The
 * amount, plan and buyer are NOT here — those were fixed server-side when the order was
 * created and are not the browser's to restate.
 */
@Data
public class PaymentAttemptEventRequest {

    /**
     * One of OPENED, ABANDONED, FAILED, VERIFY_FAILED. Anything else — PAID above all —
     * is rejected: a browser does not get to declare a payment good.
     */
    @NotBlank
    private String status;

    /** {@code window.location.href} — the page the Pay button was on. */
    @Size(max = 2048)
    private String pageUrl;

    @Size(max = 2048)
    private String referrer;

    /** Present on a FAILED event: Razorpay creates a payment id even for a refused card. */
    @Size(max = 255)
    private String paymentId;

    // Razorpay's `payment.failed` error object, forwarded field for field.
    @Size(max = 128)
    private String errorCode;

    @Size(max = 1024)
    private String errorDescription;

    /** customer / business / bank / gateway. */
    @Size(max = 128)
    private String errorSource;

    /** payment_initiation, payment_authentication, payment_authorization… */
    @Size(max = 128)
    private String errorStep;

    @Size(max = 255)
    private String errorReason;
}
