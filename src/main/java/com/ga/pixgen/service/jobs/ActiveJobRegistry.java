package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the jobs currently running on this JVM and enforces the two
 * concurrency caps that live above the database: the bounded worker pool
 * (via a fair {@link Semaphore}) and the per-user active-jobs ceiling
 * (via a {@link ConcurrentHashMap} of {@link AtomicInteger} counters).
 *
 * <p>The poller calls {@link #tryRegister(Long, Long)} <em>before</em> it
 * submits the worker task. A successful registration consumes one
 * semaphore permit and one slot of the user's per-user budget; the
 * matching {@link #release(Long)} call (issued by the worker in its
 * {@code finally}) returns both. Rejections are guaranteed not to leak
 * permits or counters.</p>
 *
 * <p>An ordered {@link ConcurrentLinkedQueue} mirrors the registration
 * order so that the controller can list active jobs and compute queue
 * positions without scanning the database. The {@code volatile shuttingDown}
 * flag is flipped from {@link #shutdown()} (wired to {@link PreDestroy})
 * so the poller stops claiming new work as the JVM drains.</p>
 */
@Component
public class ActiveJobRegistry {

    private final JobsProperties properties;
    private final Semaphore instanceSemaphore;

    private final ConcurrentHashMap<Long, ActiveJobHandle> activeByJobId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> orderedActiveIds = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, AtomicInteger> userActiveCounts = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown;

    public ActiveJobRegistry(JobsProperties properties,
                             @Qualifier("instanceSemaphore") Semaphore instanceSemaphore) {
        this.properties = properties;
        this.instanceSemaphore = instanceSemaphore;
    }

    /**
     * Atomically reserve a slot for the given {@code (jobId, userId)} pair.
     * Returns the freshly-created {@link ActiveJobHandle} on success, or an
     * empty optional if the registry is shutting down, the instance pool is
     * saturated, or the user is already at {@code maxActiveJobsPerUser}.
     * Permits are always released on rejection so callers can retry safely.
     *
     * @param jobId the job id value
     * @param userId the user id value
     * @return the Optional<ActiveJobHandle> result
     */
    public Optional<ActiveJobHandle> tryRegister(Long jobId, Long userId) {
        if (shuttingDown) {
            return Optional.empty();
        }
        if (!instanceSemaphore.tryAcquire()) {
            return Optional.empty();
        }
        AtomicInteger counter = userActiveCounts.computeIfAbsent(userId, k -> new AtomicInteger());
        int max = properties.getMaxActiveJobsPerUser();
        while (true) {
            int current = counter.get();
            if (current >= max) {
                instanceSemaphore.release();
                return Optional.empty();
            }
            if (counter.compareAndSet(current, current + 1)) {
                break;
            }
        }
        ActiveJobHandle handle = new ActiveJobHandle(jobId, userId, Instant.now());
        ActiveJobHandle existing = activeByJobId.putIfAbsent(jobId, handle);
        if (existing != null) {
            // Extremely unlikely (same jobId registered twice). Roll everything back.
            counter.decrementAndGet();
            instanceSemaphore.release();
            return Optional.empty();
        }
        orderedActiveIds.offer(jobId);
        return Optional.of(handle);
    }

    /**
     * Attach the worker {@link Future} produced by the executor. Done after
     * registration so the worker can be cancelled mid-flight.
     *
     * @param jobId the job id value
     * @param future the future value
     */
    public void attachFuture(Long jobId, Future<?> future) {
        ActiveJobHandle handle = activeByJobId.get(jobId);
        if (handle != null) {
            handle.setFuture(future);
        }
    }

    /**
     * Mark the local handle as cancel-requested and interrupt its worker
     * thread (if a future is attached). Returns {@code false} if the job
     * is not running on this instance, in which case the caller should
     * persist a {@code cancel_requested} flag in the database for the
     * owning instance to observe.
     *
     * @param jobId the job id value
     * @return the boolean result
     */
    public boolean requestCancel(Long jobId) {
        ActiveJobHandle handle = activeByJobId.get(jobId);
        if (handle == null) {
            return false;
        }
        handle.markCancelRequested();
        Future<?> future = handle.getFuture();
        if (future != null) {
            future.cancel(true);
        }
        return true;
    }

    /**
     * Release the slot previously taken by {@link #tryRegister(Long, Long)}.
     * Idempotent: a second call for the same job is a no-op so the worker's
     * {@code finally} block can run safely even after explicit cancellation.
     *
     * @param jobId the job id value
     */
    public void release(Long jobId) {
        ActiveJobHandle removed = activeByJobId.remove(jobId);
        if (removed == null) {
            return;
        }
        orderedActiveIds.remove(jobId);
        AtomicInteger counter = userActiveCounts.get(removed.getUserId());
        if (counter != null) {
            counter.decrementAndGet();
        }
        instanceSemaphore.release();
    }

    public Optional<ActiveJobHandle> get(Long jobId) {
        return Optional.ofNullable(activeByJobId.get(jobId));
    }

    /**
     * Snapshot of currently-active job ids in registration order.
     *
     * @return the List<Long> result
     */
    public List<Long> activeJobIds() {
        return new ArrayList<>(orderedActiveIds);
    }

    public int activeCountForUser(Long userId) {
        AtomicInteger counter = userActiveCounts.get(userId);
        return counter == null ? 0 : counter.get();
    }

    public int totalActive() {
        return activeByJobId.size();
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Flip the volatile shutdown flag so the poller stops claiming new work.
     */
    @PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
    }
}
