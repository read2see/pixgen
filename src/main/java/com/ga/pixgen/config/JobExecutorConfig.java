package com.ga.pixgen.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Semaphore;

/**
 * Wires the bounded worker pool used by {@code JobScheduler} and the fairness
 * semaphore guarded by the poller. Sized from {@link JobsProperties} so the
 * effective concurrency on this JVM never exceeds {@code maxJobsPerInstance}.
 *
 * <p>{@link EnableScheduling} is enabled here so the {@code @Scheduled}
 * poller is picked up without polluting the application bootstrap class.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({JobsProperties.class, ImagesProperties.class, ProfileImagesProperties.class})
@RequiredArgsConstructor
public class JobExecutorConfig {

    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    private final JobsProperties jobsProperties;

    /**
     * Bounded worker pool. Core and max are pinned to {@code maxJobsPerInstance}
     * so the queue absorbs bursts up to the same depth before the scheduler
     * back-pressures via the {@link #instanceSemaphore() semaphore}.
     *
     * @return the ThreadPoolTaskExecutor result
     */
    @Bean(name = "jobWorkerExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor jobWorkerExecutor() {
        int workers = jobsProperties.getMaxJobsPerInstance();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(workers);
        executor.setThreadNamePrefix("job-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * Fair semaphore acquired by the scheduler before submitting work to the
     * pool, released by the worker in its {@code finally} block. Acts as the
     * back-pressure ceiling so the queue never grows unbounded under load.
     *
     * @return the Semaphore result
     */
    @Bean
    public Semaphore instanceSemaphore() {
        return new Semaphore(jobsProperties.getMaxJobsPerInstance(), true);
    }
}
