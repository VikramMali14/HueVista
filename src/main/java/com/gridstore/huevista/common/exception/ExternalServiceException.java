package com.gridstore.huevista.common.exception;

/**
 * Thrown when an upstream third-party service (Claude, Replicate, …) fails,
 * times out, or returns an unusable response. Mapped to HTTP 502 Bad Gateway
 * by {@link GlobalExceptionHandler} so clients can tell "the AI provider is
 * down, retry later" apart from a genuine bug in our own code (500).
 */
public class ExternalServiceException extends RuntimeException {
    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
