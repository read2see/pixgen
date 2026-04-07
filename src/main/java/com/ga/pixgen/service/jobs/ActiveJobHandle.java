package com.ga.pixgen.service.jobs;

import lombok.Getter;

import java.time.Instant;
import java.util.concurrent.Future;

/**
 * In-memory handle to a job currently being executed on this JVM. The
 * {@code volatile} fields are mutated by the worker thread and observed by
 * the controller / scheduler threads without holding a lock; the registry
 * publishes the handle to other threads only after construction.
 */
public final class ActiveJobHandle {

    @Getter
    private final Long jobId;

    @Getter
    private final Long userId;

    @Getter
    private final Instant startedAt;

    private volatile Future<?> future;

    private volatile boolean cancelRequested;

    ActiveJobHandle(Long jobId, Long userId, Instant startedAt) {
        this.jobId = jobId;
        this.userId = userId;
        this.startedAt = startedAt;
    }

    /**
     * True once a cancel has been requested for this job.
     *
     * @return whether cancel requested
     */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * The submitted worker future, if it has been attached yet.
     *
     * @return the future
     */
    public Future<?> getFuture() {
        return future;
    }

    void setFuture(Future<?> future) {
        this.future = future;
    }

    void markCancelRequested() {
        this.cancelRequested = true;
    }
}
