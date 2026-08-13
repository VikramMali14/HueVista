package com.gridstore.huevista.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A ceiling on how many predictions this process has in flight on Replicate at once.
 *
 * <p>Worth being honest about what this does and does not buy. The error that motivated it
 * — {@code ModelRateLimitError ... high demand (E003)} — is mostly Replicate telling us the
 * MODEL is out of capacity across everyone using it, and no amount of politeness on our side
 * makes Nano Banana Pro less busy. Retrying and failing over is what actually rescues those
 * renders; this gate is the smaller, second thing.
 *
 * <p>What it does buy is the half of the problem that IS ours. The AI executor runs up to
 * sixteen threads and hands overflow to the caller, so a burst of renders could put twenty-odd
 * simultaneous predictions on one account — enough to trip an account-level limit that has
 * nothing to do with global demand, and enough that when the model IS short of capacity we
 * are competing with ourselves for it. Holding the number down converts that from twenty
 * failures into a short wait.
 *
 * <p>The wait is bounded rather than indefinite. A render that cannot even get a permit
 * inside {@code acquire-timeout-ms} is reported as retryable, because by then the queue in
 * front of it is the problem and the caller's backoff is a better place to spend the time
 * than a lock.
 */
@Slf4j
@Component
public class ReplicateConcurrencyGate {

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long acquireTimeoutMs;

    public ReplicateConcurrencyGate(
            @Value("${replicate.max-concurrent:6}") int maxConcurrent,
            @Value("${replicate.acquire-timeout-ms:120000}") long acquireTimeoutMs) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.acquireTimeoutMs = Math.max(1_000L, acquireTimeoutMs);
        // Fair, so a render that has already waited is not overtaken by one that just
        // arrived. Unfair semaphores are faster and the throughput difference here is
        // noise next to a minute of model time, but starving one customer is not.
        this.permits = new Semaphore(this.maxConcurrent, true);
    }

    /**
     * Run {@code work} with a permit held, waiting for one if they are all out.
     *
     * @throws ImageEditException RETRY when no permit came free in time — the work never
     *         ran, so the caller is free to back off and ask again.
     */
    public <T> T call(String label, Callable<T> work) throws Exception {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ImageEditException.retry(label + " was interrupted waiting for a Replicate slot.");
        }
        if (!acquired) {
            throw ImageEditException.retry(label + " waited " + acquireTimeoutMs
                    + "ms for one of the " + maxConcurrent + " Replicate slots and got none.");
        }
        try {
            return work.call();
        } finally {
            permits.release();
        }
    }

    /** For logging a backlog that is worth knowing about. */
    public int waiting() {
        return permits.getQueueLength();
    }
}
