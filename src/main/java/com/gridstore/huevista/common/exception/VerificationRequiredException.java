package com.gridstore.huevista.common.exception;

/**
 * Thrown when an action requires the user to have verified BOTH their email and
 * mobile number first — e.g. creating their first project on a trial.
 *
 * Mapped to HTTP 403 Forbidden with a {@code "code":"VERIFICATION_REQUIRED"} field
 * by {@link GlobalExceptionHandler}, so the frontend can tell it apart from other
 * 403s and surface the "verify your account" UI rather than a generic error.
 */
public class VerificationRequiredException extends RuntimeException {
    public VerificationRequiredException(String message) {
        super(message);
    }
}
