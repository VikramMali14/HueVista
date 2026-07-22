package com.gridstore.huevista.common.exception;

/**
 * A retailer's monthly image allowance (plan quota + any purchased pay-per-image
 * credits) is spent. A specialised {@link QuotaExceededException} so it is still
 * HTTP 402, but tagged with {@code "code":"IMAGE_LIMIT_REACHED"} so the frontend
 * can offer the Rs. 50 buy-one-extra-image checkout (or an upgrade) instead
 * of a dead-end error.
 */
public class ImageLimitReachedException extends QuotaExceededException {
    public ImageLimitReachedException(String message) {
        super(message);
    }
}
