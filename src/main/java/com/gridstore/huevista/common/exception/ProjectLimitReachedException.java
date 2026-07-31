package com.gridstore.huevista.common.exception;

/**
 * The account's monthly project allowance is spent. A specialised
 * {@link QuotaExceededException} so it is still HTTP 402, but tagged with
 * {@code "code":"PROJECT_LIMIT_REACHED"} so the frontend can offer the
 * buy-one-extra-project flow at the plan's own rate instead of a dead end.
 *
 * <p>There is no sibling "auto-mask unavailable" refusal any more: a project covers the
 * clean-up and the AI wall detection together, so an account that can start a project can
 * always finish it. The old split could refuse the mask AFTER the clean-up had been paid
 * for and run.
 */
public class ProjectLimitReachedException extends QuotaExceededException {
    public ProjectLimitReachedException(String message) {
        super(message);
    }
}
