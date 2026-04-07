package com.ga.pixgen.service.jobs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link UserJobLocks}, the per-user striped {@link ReentrantLock}
 * registry used to serialize the success-completion critical section
 * (deduct credits + flip job + persist image) on a single user.
 *
 * <p>The contract is two-fold: same user always gets the same lock instance
 * (so two concurrent worker threads on the same user actually serialize),
 * and different users always get different locks (so unrelated users never
 * block each other).</p>
 */
class UserJobLocksTest {

    private final UserJobLocks locks = new UserJobLocks();

    @Test
    void forUser_returnsSameLockInstance_forSameUserId() {
        ReentrantLock first = locks.forUser(42L);
        ReentrantLock second = locks.forUser(42L);

        assertThat(first).isSameAs(second);
    }

    @Test
    void forUser_returnsDistinctLockInstances_forDifferentUserIds() {
        ReentrantLock alice = locks.forUser(1L);
        ReentrantLock bob = locks.forUser(2L);

        assertThat(alice).isNotSameAs(bob);
    }

    @Test
    void withLock_serializesCriticalSection_forSameUser() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicBoolean overlap = new AtomicBoolean(false);
        AtomicInteger executed = new AtomicInteger();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        locks.withLock(99L, () -> {
                            if (active.incrementAndGet() > 1) {
                                overlap.set(true);
                            }
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            executed.incrementAndGet();
                            active.decrementAndGet();
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(overlap)
                .as("withLock must never let two threads enter the critical section for the same user")
                .isFalse();
        assertThat(executed.get()).isEqualTo(threads);
    }

    @Test
    void withLock_doesNotSerialize_forDifferentUsers() throws Exception {
        // If two users were sharing a lock, the second runnable could not enter
        // until the first releases. We prove independence by having user A hold
        // the lock and asserting user B can still enter and finish.
        ReentrantLock lockA = locks.forUser(1L);
        lockA.lock();
        try {
            CountDownLatch entered = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                pool.submit(() -> locks.withLock(2L, entered::countDown));
                assertThat(entered.await(2, TimeUnit.SECONDS))
                        .as("a held lock for user 1 must not block user 2")
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }
        } finally {
            lockA.unlock();
        }
    }

    @Test
    void withLock_releasesLock_evenWhenBodyThrows() {
        ReentrantLock lock = locks.forUser(7L);

        try {
            locks.withLock(7L, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // expected
        }

        assertThat(lock.isLocked())
                .as("the lock must be released after the body throws so the next caller can enter")
                .isFalse();
    }
}
