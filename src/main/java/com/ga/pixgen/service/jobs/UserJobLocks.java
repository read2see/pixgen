package com.ga.pixgen.service.jobs;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-user striped {@link ReentrantLock} registry. The success-completion
 * critical section in the worker — deduct credits, flip {@code Job} to
 * {@code SUCCEEDED}, persist the {@code Image} — must be serialized for a
 * single user so two parallel jobs of the same user cannot race the
 * conditional credit {@code UPDATE} into a negative balance, but jobs
 * belonging to different users must remain independent.
 *
 * <p>Locks are created lazily and kept in a {@link ConcurrentHashMap} keyed
 * by user id. Once a user has held a lock once, the same instance is
 * returned forever, so callers can store the reference if they need to
 * reuse it across a transaction boundary. The map is unbounded by design:
 * the population is bounded by the number of distinct users that have
 * ever submitted a job during the lifetime of the JVM, which is small
 * relative to live data.</p>
 */
@Component
public class UserJobLocks {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Returns the per-user lock, creating it on first access.
     *
     * @param userId the user id value
     * @return the ReentrantLock result
     */
    public ReentrantLock forUser(Long userId) {
        return locks.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    /**
     * Run {@code body} while holding the per-user lock; releases on every exit path.
     *
     * @param userId the user id value
     * @param body the body value
     */
    public void withLock(Long userId, Runnable body) {
        ReentrantLock lock = forUser(userId);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }
}
