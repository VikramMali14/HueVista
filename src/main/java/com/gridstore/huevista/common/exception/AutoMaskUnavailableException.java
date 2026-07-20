package com.gridstore.huevista.common.exception;

/**
 * The AI auto-mask step can't run for this account: either the plan doesn't
 * include auto-masking at all (Starter is manual-masking only) or the monthly
 * auto-mask allowance is spent. A specialised {@link QuotaExceededException} so
 * it is still HTTP 402, but tagged with {@code "code":"AUTO_MASK_UNAVAILABLE"}
 * so the frontend can steer the user to manual masking (free on every tier) or
 * to an upgrade, instead of a generic payment error.
 */
public class AutoMaskUnavailableException extends QuotaExceededException {
    public AutoMaskUnavailableException(String message) {
        super(message);
    }
}
