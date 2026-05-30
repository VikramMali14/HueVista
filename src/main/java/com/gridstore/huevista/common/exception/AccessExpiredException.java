package com.gridstore.huevista.common.exception;

/**
 * Thrown when a CUSTOMER's time-limited access has expired (or was never set up).
 * Mapped to HTTP 403 Forbidden by {@link GlobalExceptionHandler}. Per the "lock
 * everything on expiry" policy, this blocks both creating and managing projects.
 */
public class AccessExpiredException extends RuntimeException {
    public AccessExpiredException(String message) {
        super(message);
    }
}
