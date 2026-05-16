package com.gridstore.huevista.project.queue;

import com.gridstore.huevista.project.service.SegmentationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class SegmentationQueueWorker {

    private static final long POLL_INTERVAL_MS = 2_000;
    private static final long REDIS_ERROR_BACKOFF_MS = 15_000;

    private final SegmentationJobQueue jobQueue;
    private final SegmentationService segmentationService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService workerThread;

    @PostConstruct
    public void start() {
        running.set(true);
        workerThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "segmentation-queue-worker");
            t.setDaemon(true);
            return t;
        });
        workerThread.submit(this::processLoop);
        log.info("Segmentation queue worker started");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) workerThread.shutdownNow();
        log.info("Segmentation queue worker stopped");
    }

    private void processLoop() {
        while (running.get()) {
            try {
                SegmentationJob job = jobQueue.dequeue();
                if (job != null) {
                    log.info("Processing segmentation job: project={}", job.getProjectId());
                    segmentationService.segmentAsync(job.getProjectId(), job.getImageUrl());
                } else {
                    sleep(POLL_INTERVAL_MS);
                }
            } catch (DataAccessException e) {
                // Redis unavailable — back off and retry rather than spam logs
                log.warn("Redis unavailable for segmentation queue, retrying in {}s: {}",
                        REDIS_ERROR_BACKOFF_MS / 1000, e.getMessage());
                sleep(REDIS_ERROR_BACKOFF_MS);
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Segmentation queue worker unexpected error: {}", e.getMessage(), e);
                    sleep(POLL_INTERVAL_MS);
                }
            }
        }
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
