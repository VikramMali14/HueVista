package com.gridstore.huevista.common.exception;

/**
 * A retailer needs to start (or upgrade to) a PAID plan subscription to continue —
 * e.g. they've used the single project their free trial includes, or the trial has
 * ended. A specialised {@link QuotaExceededException} so it is still HTTP 402, but
 * tagged with {@code "code":"SUBSCRIPTION_REQUIRED"} so the frontend routes them to
 * pricing rather than the customer "buy one extra project" flow (a plain 402).
 */
public class SubscriptionRequiredException extends QuotaExceededException {
    public SubscriptionRequiredException(String message) {
        super(message);
    }
}
