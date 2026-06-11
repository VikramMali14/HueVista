package com.gridstore.huevista.common.exception;

/**
 * Thrown when a blocking operation (e.g. waiting on a SAM 2 segmentation poll)
 * is interrupted — typically during shutdown or thread-pool cancellation.
 * Callers must re-set the interrupt flag ({@code Thread.currentThread().interrupt()})
 * before throwing. Mapped to HTTP 503 Service Unavailable by
 * {@link GlobalExceptionHandler} since the request can simply be retried.
 */
public class ProcessingInterruptedException extends RuntimeException {
    public ProcessingInterruptedException(String message) {
        super(message);
    }

    public ProcessingInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
