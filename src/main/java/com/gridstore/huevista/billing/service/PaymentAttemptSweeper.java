package com.gridstore.huevista.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Closes checkout attempts that never reported an outcome.
 *
 * <p>An attempt sits at CREATED or OPENED only while the buyer has a Checkout window in
 * front of them, which is minutes. Anything still open an hour later did not come back —
 * the tab was closed mid-payment, the browser crashed, the network dropped, or our own
 * telemetry call was blocked. All of those are abandonment, and all of them would
 * otherwise sit in the report looking like payments still in flight.
 *
 * <p>That matters more than tidiness: the abandonment count is the number this report
 * exists to get right, and leaving these open would quietly under-report it. The sweeper
 * writes a distinct timeline note so a row closed here is never mistaken for one where
 * the buyer was actually seen clicking away.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAttemptSweeper {

    private final PaymentAttemptService paymentAttemptService;

    /** Grace period before an unreported attempt is presumed abandoned. */
    @Value("${app.payment-audit.stale-after-minutes:60}")
    private long staleAfterMinutes;

    /** Cap per run, so a backlog is worked through in batches instead of one long transaction. */
    @Value("${app.payment-audit.sweep-batch:500}")
    private int sweepBatch;

    /** Every 15 minutes — often enough that the report is never far behind reality. */
    @Scheduled(cron = "0 */15 * * * *")
    public void run() {
        try {
            paymentAttemptService.closeStale(Duration.ofMinutes(Math.max(5, staleAfterMinutes)),
                    Math.max(1, sweepBatch));
        } catch (Exception e) {
            // Bookkeeping: a failed sweep must never take the scheduler down with it.
            log.warn("Payment attempt sweep failed: {}", e.getMessage());
        }
    }
}
