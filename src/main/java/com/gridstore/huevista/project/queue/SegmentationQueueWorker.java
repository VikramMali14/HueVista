package com.gridstore.huevista.project.queue;

import com.gridstore.huevista.project.service.SegmentationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls Redis for queued segmentation jobs and runs them one at a time.
 *
 * <h2>Why this is a {@link SmartLifecycle} and not {@code @PostConstruct}/{@code @PreDestroy}</h2>
 * The queue reads Redis through {@code LettuceConnectionFactory}, which is itself a
 * {@code SmartLifecycle} at phase 0 — and Spring runs the whole lifecycle STOP pass
 * before it starts calling {@code @PreDestroy}. So a worker that only stopped on
 * {@code @PreDestroy} was still looping while the connection factory underneath it had
 * already been shut down: every context close logged
 * {@code ERROR ... unexpected error: LettuceConnectionFactory has been STOPPED} with a
 * full stack trace. The {@code running} flag could not suppress it, because nothing had
 * cleared the flag yet.
 *
 * Stopping in the lifecycle pass at {@link Integer#MAX_VALUE} fixes the ordering at the
 * source: beans are stopped in DESCENDING phase order, so this worker is torn down
 * before anything it depends on. That matters beyond the log noise — a job dequeued in
 * that window would have been taken off the queue and then dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class SegmentationQueueWorker implements SmartLifecycle {

    private static final long POLL_INTERVAL_MS = 2_000;
    private static final long REDIS_ERROR_BACKOFF_MS = 15_000;
    private static final long STALE_CHECK_INTERVAL_MS = 60_000;
    /** How long to let an in-flight poll finish before giving up on a clean stop. */
    private static final long SHUTDOWN_GRACE_MS = 5_000;

    private final SegmentationJobQueue jobQueue;
    private final SegmentationService segmentationService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService workerThread;
    private long lastStaleCheckMs = 0;

    /**
     * Stopped first, started last. Everything this worker touches — the Redis connection
     * factory above all — lives at a lower phase, and Spring stops high phases first.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workerThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "segmentation-queue-worker");
            t.setDaemon(true);
            return t;
        });
        workerThread.submit(this::processLoop);
        log.info("Segmentation queue worker started");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (workerThread != null) {
            // Interrupt the poll, then WAIT for it. Returning before the loop has
            // actually exited would hand the ordering guarantee straight back: Spring
            // would carry on and stop Redis while this thread was still mid-dequeue.
            workerThread.shutdownNow();
            try {
                if (!workerThread.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    log.warn("Segmentation queue worker did not stop within {}ms",
                            SHUTDOWN_GRACE_MS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Segmentation queue worker stopped");
    }

    private void processLoop() {
        while (running.get()) {
            try {
                // Recover jobs whose worker crashed after dequeue but before ack.
                long now = System.currentTimeMillis();
                if (now - lastStaleCheckMs >= STALE_CHECK_INTERVAL_MS) {
                    lastStaleCheckMs = now;
                    jobQueue.requeueStale();
                }

                SegmentationJob job = jobQueue.dequeue();
                if (job != null) {
                    log.info("Processing segmentation job: project={}", job.getProjectId());
                    segmentationService.segmentAsync(job.getProjectId(), job.getImageUrl());
                } else {
                    sleep(POLL_INTERVAL_MS);
                }
            } catch (DataAccessException e) {
                if (stopping()) break;
                // Redis unavailable — back off and retry rather than spam logs
                log.warn("Redis unavailable for segmentation queue, retrying in {}s: {}",
                        REDIS_ERROR_BACKOFF_MS / 1000, e.getMessage());
                sleep(REDIS_ERROR_BACKOFF_MS);
            } catch (Exception e) {
                if (stopping()) break;
                log.error("Segmentation queue worker unexpected error: {}", e.getMessage(), e);
                sleep(POLL_INTERVAL_MS);
            }
        }
    }

    /**
     * Is this failure just the shutdown we asked for?
     *
     * Checked before logging anything, because a stop interrupts whatever the loop was
     * doing and the resulting exception is expected, not news. The interrupt flag is
     * consulted alongside {@code running} so a stop that lands mid-call is recognised
     * even in the window before the flag is observed on this thread.
     */
    private boolean stopping() {
        return !running.get() || Thread.currentThread().isInterrupted();
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
