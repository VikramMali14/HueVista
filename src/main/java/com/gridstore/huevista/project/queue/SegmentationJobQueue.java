package com.gridstore.huevista.project.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Component
public class SegmentationJobQueue {

    private static final String QUEUE_KEY = "huevista:segmentation:jobs";

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

    public SegmentationJob dequeue() {
        String payload = redisTemplate.opsForList().rightPop(QUEUE_KEY);
        if (payload == null) return null;
        int sep = payload.indexOf('|');
        if (sep < 0) return null;
        return new SegmentationJob(payload.substring(0, sep), payload.substring(sep + 1));
    }

    public long size() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }
}
