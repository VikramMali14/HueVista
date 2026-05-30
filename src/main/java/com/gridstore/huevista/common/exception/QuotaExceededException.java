package com.gridstore.huevista.common.exception;

/**
 * Thrown when a user has no active subscription or has exhausted their monthly AI
 * generation quota. Mapped to HTTP 402 Payment Required by {@link GlobalExceptionHandler}.
 *
 * <p>This is deliberately distinct from {@link IllegalStateException} (now → 409 Conflict)
 * so that genuine state conflicts ("already have a subscription", "segmentation already
 * in progress") are not mislabelled as payment errors.
 */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
