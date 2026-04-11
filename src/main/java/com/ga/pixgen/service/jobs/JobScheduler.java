package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * {@code @Scheduled} poller that drains {@code PENDING} rows from the jobs
 * table and dispatches them to the worker pool.
 *
 * <p>Each tick the scheduler:</p>
 * <ol>
 *     <li>Bails immediately if the {@link ActiveJobRegistry} has been
 *         flipped into shutdown mode (so a graceful shutdown drains the
 *         pool without claiming new work).</li>
 *     <li>Sizes the claim by the available permits on the bounded
 *         {@link Semaphore}; this is the single-source-of-truth for
 *         "how many jobs can run on this JVM right now".</li>
 *     <li>Issues the atomic {@code FOR UPDATE SKIP LOCKED} query inside
 *         a {@code @Transactional} method so two pollers (across
 *         instances) cannot grab the same row.</li>
 *     <li>For each claimed row, attempts to register a slot with the
 *         registry — that call also enforces the per-user active cap. A
 *         rejection leaves the row {@code PENDING} so the next poll can
 *         retry it without losing the database lock.</li>
 *     <li>Successfully-registered rows are flipped to {@code RUNNING},
 *         tagged with this instance's id, broadcast as a status event,
 *         and submitted to the worker pool with the resulting future
 *         attached to the registry handle for cancellation.</li>
 *     <li>If the executor itself rejects the submission the registry
 *         slot is released so the semaphore and per-user counters do
 *         not leak.</li>
 * </ol>
 */
@Component
public class JobScheduler {

    private final JobRepository jobRepository;
    private final ActiveJobRegistry registry;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore instanceSemaphore;
    private final JobsProperties properties;
    private final JobWorker worker;
    private final JobEventBroker broker;

    public JobScheduler(JobRepository jobRepository,
                        ActiveJobRegistry registry,
                        @Qualifier("jobWorkerExecutor") ThreadPoolTaskExecutor executor,
                        @Qualifier("instanceSemaphore") Semaphore instanceSemaphore,
                        JobsProperties properties,
                        JobWorker worker,
                        JobEventBroker broker) {
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.executor = executor;
        this.instanceSemaphore = instanceSemaphore;
        this.properties = properties;
        this.worker = worker;
        this.broker = broker;
    }

    /**
     * Single poll iteration. Public so unit tests can drive it
     * deterministically without waiting for the {@code @Scheduled}
     * trigger.
     */
    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:1000}")
    @Transactional
    public void poll() {
        if (registry.isShuttingDown()) {
            return;
        }
        int slots = instanceSemaphore.availablePermits();
        if (slots <= 0) {
            return;
        }
        List<Job> claimed = jobRepository.claimNextPending(slots);
        for (Job job : claimed) {
            dispatch(job);
        }
    }

    private void dispatch(Job job) {
        Optional<ActiveJobHandle> handle = registry.tryRegister(job.getId(), job.getUserId());
        if (handle.isEmpty()) {
            // Either the registry is shutting down, the semaphore raced
            // empty (another poller would have to be running on this JVM
            // — exotic), or the user is at the per-user cap. Leave the
            // row PENDING so the next tick can revisit it.
            return;
        }
        boolean dispatched = false;
        try {
            Instant now = Instant.now();
            job.setStatus(JobStatus.RUNNING);
            job.setClaimedByInstance(properties.getInstanceId());
            job.setClaimedAt(now);
            job.setStartedAt(now);
            jobRepository.save(job);

            broker.publishStatus(job.getId(), job.getUserId(), JobStatus.RUNNING);

            Future<?> future = executor.submit(() -> worker.execute(job));
            registry.attachFuture(job.getId(), future);
            dispatched = true;
        } finally {
            if (!dispatched) {
                registry.release(job.getId());
            }
        }
    }
}
