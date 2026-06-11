package com.gridstore.huevista.project.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Redis-backed segmentation queue using the reliable-queue pattern: dequeue
 * atomically moves the payload from the pending list to a processing list
 * (RPOPLPUSH), and the job is only removed once {@link #acknowledge} is called
 * after the work finishes. If the worker JVM crashes mid-job, the payload is
 * still in the processing list and {@link #requeueStale} moves it back to the
 * pending queue, instead of the job being lost and the project hanging in
 * SEGMENTING forever. Jobs that keep going stale are dropped after
 * {@code MAX_ATTEMPTS} so a poison payload can't loop indefinitely.
 */
@Slf4j
@Component
public class SegmentationJobQueue {

    private static final String QUEUE_KEY = "huevista:segmentation:jobs";
    private static final String PROCESSING_KEY = "huevista:segmentation:processing";
    private static final String META_KEY = "huevista:segmentation:processing-started";
    private static final String ATTEMPTS_KEY = "huevista:segmentation:attempts";

    /** A job in the processing list longer than this is presumed crashed. */
    public static final long STALE_AFTER_MS = 10 * 60 * 1000;
    public static final int MAX_ATTEMPTS = 3;

    private final StringRedisTemplate redisTemplate;
    private final long maxDepth;

    public SegmentationJobQueue(StringRedisTemplate redisTemplate,
                                @Value("${app.segmentation.queue.max-depth:500}") long maxDepth) {
        this.redisTemplate = redisTemplate;
        this.maxDepth = maxDepth;
    }

    /**
     * Adds a job to the queue. Load management: if the backlog already exceeds
     * {@code app.segmentation.queue.max-depth}, reject with 503 so the caller is
     * told to retry later instead of letting the queue (and downstream AI spend)
     * grow without bound under a burst.
     */
    public void enqueue(String projectId, String imageUrl) {
        long depth = size();
        if (maxDepth > 0 && depth >= maxDepth) {
            log.warn("Segmentation queue full (depth={} >= max={}), rejecting project={}",
                    depth, maxDepth, projectId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The segmentation queue is busy right now. Please try again in a few minutes.");
        }
        String payload = projectId + "|" + imageUrl;
        redisTemplate.opsForList().leftPush(QUEUE_KEY, payload);
        log.info("Segmentation job enqueued: project={} (queue depth now ~{})", projectId, depth + 1);
    }

    /**
     * Atomically moves the oldest pending job to the processing list and returns
     * it. The caller must call {@link #acknowledge} when the job is done (success
     * or handled failure), otherwise {@link #requeueStale} will eventually retry it.
     */
    public SegmentationJob dequeue() {
        String payload = redisTemplate.opsForList().rightPopAndLeftPush(QUEUE_KEY, PROCESSING_KEY);
        if (payload == null) return null;
        redisTemplate.opsForHash().put(META_KEY, payload, String.valueOf(System.currentTimeMillis()));
        redisTemplate.opsForHash().increment(ATTEMPTS_KEY, payload, 1);
        int sep = payload.indexOf('|');
        if (sep < 0) {
            log.warn("Discarding malformed segmentation payload: {}", payload);
            acknowledgePayload(payload);
            return null;
        }
        return new SegmentationJob(payload.substring(0, sep), payload.substring(sep + 1));
    }

    /** Marks a previously dequeued job as finished, removing it from the processing list. */
    public void acknowledge(String projectId, String imageUrl) {
        acknowledgePayload(projectId + "|" + imageUrl);
    }

    private void acknowledgePayload(String payload) {
        redisTemplate.opsForList().remove(PROCESSING_KEY, 1, payload);
        redisTemplate.opsForHash().delete(META_KEY, payload);
        redisTemplate.opsForHash().delete(ATTEMPTS_KEY, payload);
    }

    /**
     * Returns unacknowledged jobs that have been in-flight too long (worker
     * crash/restart) to the pending queue, dropping any that have already been
     * retried {@link #MAX_ATTEMPTS} times.
     */
    public void requeueStale() {
        Map<Object, Object> started = redisTemplate.opsForHash().entries(META_KEY);
        if (started == null || started.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Object, Object> entry : started.entrySet()) {
            String payload = String.valueOf(entry.getKey());
            long startedAt = parseLongOr(entry.getValue(), now);
            if (now - startedAt < STALE_AFTER_MS) continue;

            long attempts = parseLongOr(redisTemplate.opsForHash().get(ATTEMPTS_KEY, payload), 1);
            if (attempts >= MAX_ATTEMPTS) {
                log.error("Dropping segmentation job after {} attempts (poison or repeated crashes): {}",
                        attempts, payload);
                acknowledgePayload(payload);
            } else {
                log.warn("Requeueing stale segmentation job (attempt {} of {}): {}",
                        attempts, MAX_ATTEMPTS, payload);
                redisTemplate.opsForList().remove(PROCESSING_KEY, 1, payload);
                redisTemplate.opsForHash().delete(META_KEY, payload);
                redisTemplate.opsForList().leftPush(QUEUE_KEY, payload);
            }
        }
    }

    private static long parseLongOr(Object value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long size() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }
}
