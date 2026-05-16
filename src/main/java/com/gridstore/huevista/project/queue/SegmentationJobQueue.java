package com.gridstore.huevista.project.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentationJobQueue {

    private static final String QUEUE_KEY = "huevista:segmentation:jobs";

    private final StringRedisTemplate redisTemplate;

    public void enqueue(String projectId, String imageUrl) {
        String payload = projectId + "|" + imageUrl;
        redisTemplate.opsForList().leftPush(QUEUE_KEY, payload);
        log.info("Segmentation job enqueued: project={}", projectId);
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
