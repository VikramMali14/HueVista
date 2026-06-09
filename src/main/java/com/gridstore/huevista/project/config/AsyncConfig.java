package com.gridstore.huevista.project.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${app.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:100}")
    private int queueCapacity;

    /**
     * Pool for AI / segmentation work that runs off the request thread.
     *
     * Load management:
     *  - Bounded queue (queueCapacity): work doesn't pile up without limit.
     *  - CallerRunsPolicy: when both the pool (maxPoolSize) and queue are full, the
     *    submitting thread runs the task itself. That throttles the producer
     *    (natural backpressure) instead of throwing RejectedExecutionException and
     *    silently losing a segmentation job.
     *  - Graceful shutdown: on app stop, stop accepting new work and wait up to
     *    awaitTermination seconds for in-flight tasks to finish so we don't leave
     *    half-processed projects.
     */
    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ai-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
