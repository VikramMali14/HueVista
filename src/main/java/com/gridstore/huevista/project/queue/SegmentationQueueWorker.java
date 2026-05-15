package com.gridstore.huevista.project.queue;

import com.gridstore.huevista.project.service.SegmentationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class SegmentationQueueWorker {

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
                SegmentationJob job = jobQueue.dequeue(5);
                if (job != null) {
                    log.info("Processing segmentation job: project={}", job.getProjectId());
                    segmentationService.segmentAsync(job.getProjectId(), job.getImageUrl());
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Segmentation queue worker error: {}", e.getMessage(), e);
                }
            }
        }
    }
}
