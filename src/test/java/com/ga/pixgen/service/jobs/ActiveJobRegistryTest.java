package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency tests for {@link ActiveJobRegistry}. These exercise the
 * primitives required by the plan: a fair {@link Semaphore} bounding
 * total in-flight jobs on this JVM, a {@link java.util.concurrent.ConcurrentHashMap
 * ConcurrentHashMap} of per-user {@link java.util.concurrent.atomic.AtomicInteger
 * AtomicInteger} counters, an ordered
 * {@link java.util.concurrent.ConcurrentLinkedQueue ConcurrentLinkedQueue}
 * of active job ids, and a {@code volatile} shutdown flag.
 */
class ActiveJobRegistryTest {

    private JobsProperties properties;
    private Semaphore instanceSemaphore;
    private ActiveJobRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new JobsProperties();
        properties.setMaxJobsPerInstance(2);
        properties.setMaxActiveJobsPerUser(2);
        instanceSemaphore = new Semaphore(properties.getMaxJobsPerInstance(), true);
        registry = new ActiveJobRegistry(properties, instanceSemaphore);
    }

    @Test
    void tryRegister_returnsHandle_whenSlotsAreAvailable() {
        Optional<ActiveJobHandle> handle = registry.tryRegister(101L, 7L);

        assertThat(handle).isPresent();
        assertThat(handle.get().getJobId()).isEqualTo(101L);
        assertThat(handle.get().getUserId()).isEqualTo(7L);
        assertThat(handle.get().isCancelRequested()).isFalse();
        assertThat(handle.get().getStartedAt()).isNotNull();
        assertThat(registry.totalActive()).isEqualTo(1);
        assertThat(registry.activeCountForUser(7L)).isEqualTo(1);
        assertThat(instanceSemaphore.availablePermits())
                .as("registering one job consumes one semaphore permit")
                .isEqualTo(1);
    }

    @Test
    void tryRegister_returnsEmpty_whenInstanceSemaphoreExhausted() {
        assertThat(registry.tryRegister(1L, 1L)).isPresent();
        assertThat(registry.tryRegister(2L, 2L)).isPresent();

        Optional<ActiveJobHandle> third = registry.tryRegister(3L, 3L);

        assertThat(third).isEmpty();
        assertThat(registry.totalActive()).isEqualTo(2);
        assertThat(instanceSemaphore.availablePermits()).isZero();
    }

    @Test
    void tryRegister_returnsEmpty_whenUserAtActiveLimit() {
        properties.setMaxJobsPerInstance(8);
        properties.setMaxActiveJobsPerUser(2);
        instanceSemaphore = new Semaphore(properties.getMaxJobsPerInstance(), true);
        registry = new ActiveJobRegistry(properties, instanceSemaphore);

        assertThat(registry.tryRegister(1L, 42L)).isPresent();
        assertThat(registry.tryRegister(2L, 42L)).isPresent();

        Optional<ActiveJobHandle> third = registry.tryRegister(3L, 42L);

        assertThat(third).isEmpty();
        assertThat(registry.activeCountForUser(42L)).isEqualTo(2);
        assertThat(instanceSemaphore.availablePermits())
                .as("rejecting on per-user limit must release the semaphore permit")
                .isEqualTo(properties.getMaxJobsPerInstance() - 2);
    }

    @Test
    void release_decrementsCountersAndRemovesHandle() {
        ActiveJobHandle handle = registry.tryRegister(11L, 99L).orElseThrow();

        registry.release(handle.getJobId());

        assertThat(registry.totalActive()).isZero();
        assertThat(registry.activeCountForUser(99L)).isZero();
        assertThat(registry.get(11L)).isEmpty();
        assertThat(instanceSemaphore.availablePermits())
                .as("release must return the permit to the semaphore")
                .isEqualTo(properties.getMaxJobsPerInstance());
    }

    @Test
    void release_isIdempotent_whenHandleAlreadyRemoved() {
        ActiveJobHandle handle = registry.tryRegister(11L, 99L).orElseThrow();

        registry.release(handle.getJobId());
        registry.release(handle.getJobId());

        assertThat(instanceSemaphore.availablePermits())
                .as("a second release for the same job must not over-release the semaphore")
                .isEqualTo(properties.getMaxJobsPerInstance());
        assertThat(registry.activeCountForUser(99L)).isZero();
    }

    @Test
    void requestCancel_returnsFalse_whenJobIsNotLocal() {
        boolean cancelled = registry.requestCancel(404L);

        assertThat(cancelled).isFalse();
    }

    @Test
    void requestCancel_setsFlagAndInterruptsFuture_whenJobIsLocal() {
        ActiveJobHandle handle = registry.tryRegister(55L, 7L).orElseThrow();
        Future<?> future = mock(Future.class);
        when(future.cancel(true)).thenReturn(true);
        registry.attachFuture(handle.getJobId(), future);

        boolean cancelled = registry.requestCancel(handle.getJobId());

        assertThat(cancelled).isTrue();
        assertThat(handle.isCancelRequested()).isTrue();
        verify(future).cancel(true);
    }

    @Test
    void requestCancel_stillFlipsFlag_evenWhenFutureNotYetAttached() {
        ActiveJobHandle handle = registry.tryRegister(55L, 7L).orElseThrow();

        boolean cancelled = registry.requestCancel(handle.getJobId());

        assertThat(cancelled).isTrue();
        assertThat(handle.isCancelRequested()).isTrue();
    }

    @Test
    void activeJobIds_returnsSnapshotInRegistrationOrder() {
        properties.setMaxJobsPerInstance(4);
        instanceSemaphore = new Semaphore(properties.getMaxJobsPerInstance(), true);
        registry = new ActiveJobRegistry(properties, instanceSemaphore);

        registry.tryRegister(10L, 1L);
        registry.tryRegister(11L, 2L);
        registry.tryRegister(12L, 1L);

        assertThat(registry.activeJobIds()).containsExactly(10L, 11L, 12L);

        registry.release(11L);

        assertThat(registry.activeJobIds()).containsExactly(10L, 12L);
    }

    @Test
    void shutdown_blocksFurtherRegistrationsAndDoesNotConsumeSemaphore() {
        registry.shutdown();

        Optional<ActiveJobHandle> handle = registry.tryRegister(1L, 1L);

        assertThat(handle).isEmpty();
        assertThat(registry.isShuttingDown()).isTrue();
        assertThat(instanceSemaphore.availablePermits())
                .as("registrations refused due to shutdown must not consume permits")
                .isEqualTo(properties.getMaxJobsPerInstance());
    }

    @Test
    void concurrentTryRegister_respectsInstanceLimitExactly() throws Exception {
        int workers = 16;
        int permits = 3;
        properties.setMaxJobsPerInstance(permits);
        properties.setMaxActiveJobsPerUser(permits);
        instanceSemaphore = new Semaphore(permits, true);
        registry = new ActiveJobRegistry(properties, instanceSemaphore);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger registered = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                long jobId = i + 1;
                long userId = (i % workers) + 1; // unique user per attempt to avoid the per-user cap
                futures.add(pool.submit(() -> {
                    start.await();
                    registry.tryRegister(jobId, userId).ifPresent(h -> registered.incrementAndGet());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }

            assertThat(registered.get()).isEqualTo(permits);
            assertThat(registry.totalActive()).isEqualTo(permits);
            assertThat(instanceSemaphore.availablePermits()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTryRegister_respectsPerUserLimitExactly() throws Exception {
        int workers = 16;
        int perUser = 2;
        properties.setMaxJobsPerInstance(workers);
        properties.setMaxActiveJobsPerUser(perUser);
        instanceSemaphore = new Semaphore(properties.getMaxJobsPerInstance(), true);
        registry = new ActiveJobRegistry(properties, instanceSemaphore);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger registered = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            long contendedUserId = 777L;
            for (int i = 0; i < workers; i++) {
                long jobId = i + 1;
                futures.add(pool.submit(() -> {
                    start.await();
                    registry.tryRegister(jobId, contendedUserId).ifPresent(h -> registered.incrementAndGet());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }

            assertThat(registered.get()).isEqualTo(perUser);
            assertThat(registry.activeCountForUser(contendedUserId)).isEqualTo(perUser);
            assertThat(instanceSemaphore.availablePermits())
                    .as("rejected attempts must release their permits so other users can claim")
                    .isEqualTo(properties.getMaxJobsPerInstance() - perUser);
        } finally {
            pool.shutdownNow();
        }
    }
}
