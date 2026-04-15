package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JobScheduler}, the {@code @Scheduled} poller that
 * pulls {@code PENDING} rows out of the database via
 * {@link JobRepository#claimNextPending(int)} and dispatches them to the
 * worker pool.
 *
 * <p>The plan pins a few invariants the scheduler must respect:</p>
 * <ul>
 *     <li>Never claim work while the registry is shutting down.</li>
 *     <li>Size each claim by the bounded {@link Semaphore} so the worker
 *         queue cannot grow without bound.</li>
 *     <li>Skip a job (leaving it {@code PENDING}) when the registry
 *         rejects it because of the per-user active cap.</li>
 *     <li>Mark a successfully-registered job {@code RUNNING}, emit the
 *         status event, submit the worker future, and attach it to the
 *         registry so cancellation can interrupt it later.</li>
 *     <li>Release the registry slot if the executor itself rejects the
 *         submission so the semaphore and per-user counters do not leak.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobSchedulerTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ActiveJobRegistry registry;

    @Mock
    private ThreadPoolTaskExecutor executor;

    @Mock
    private JobWorker worker;

    @Mock
    private JobEventBroker broker;

    private Semaphore instanceSemaphore;
    private JobsProperties properties;
    private JobScheduler scheduler;

    @BeforeEach
    void setUp() {
        instanceSemaphore = new Semaphore(2, true);
        properties = new JobsProperties();
        properties.setMaxJobsPerInstance(2);
        properties.setMaxActiveJobsPerUser(2);
        properties.setInstanceId("instance-A");
        scheduler = new JobScheduler(
                jobRepository,
                registry,
                executor,
                instanceSemaphore,
                properties,
                worker,
                broker);
    }

    @Test
    void poll_doesNothing_whenRegistryIsShuttingDown() {
        when(registry.isShuttingDown()).thenReturn(true);

        scheduler.poll();

        verifyNoInteractions(jobRepository);
        verifyNoInteractions(executor);
        verify(registry, never()).tryRegister(any(), any());
    }

    @Test
    void poll_doesNothing_afterSchedulerShutdownIsCalled() {
        // The scheduler's own @PreDestroy hook flips a volatile flag so the
        // poller stops claiming new work even before the registry has had a
        // chance to react. This is the application-side half of the graceful
        // shutdown contract: the executor pool drains its in-flight work
        // (configured on JobExecutorConfig) while the poller refuses to grow
        // the queue.
        scheduler.shutdown();

        scheduler.poll();

        verifyNoInteractions(jobRepository);
        verifyNoInteractions(executor);
        verify(registry, never()).tryRegister(any(), any());
        assertThat(scheduler.isShuttingDown()).isTrue();
    }

    @Test
    void recoverAbandonedJobs_requeuesRowsTaggedWithThisInstance() {
        // Startup recovery delegates to the repository query, scoped by the
        // configured instance id so abandoned RUNNING rows from a previous
        // crash of *this* JVM (and only this JVM) are flipped back to
        // PENDING. Simulating the @PostConstruct invocation directly keeps
        // the test deterministic.
        when(jobRepository.requeueRunningOwnedBy("instance-A")).thenReturn(3);

        scheduler.recoverAbandonedJobs();

        verify(jobRepository).requeueRunningOwnedBy("instance-A");
    }

    @Test
    void poll_doesNothing_whenSemaphoreHasNoAvailablePermits() throws Exception {
        instanceSemaphore.acquire(2);

        scheduler.poll();

        verifyNoInteractions(jobRepository);
        verifyNoInteractions(executor);
        verify(registry, never()).tryRegister(any(), any());
    }

    @Test
    void poll_callsClaimWithAvailablePermits_andDoesNothingWhenNoneClaimed() {
        when(jobRepository.claimNextPending(2)).thenReturn(List.of());

        scheduler.poll();

        verify(jobRepository).claimNextPending(2);
        verify(registry, never()).tryRegister(any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void poll_skipsJob_andDoesNotMarkRunning_whenRegistryRejects() {
        Job job = pendingJob(101L, 7L);
        when(jobRepository.claimNextPending(anyInt())).thenReturn(List.of(job));
        when(registry.tryRegister(101L, 7L)).thenReturn(Optional.empty());

        scheduler.poll();

        verify(jobRepository, never()).save(any());
        verifyNoInteractions(executor);
        verifyNoInteractions(broker);
        verify(registry, never()).attachFuture(any(), any());
    }

    @Test
    void poll_marksRunning_publishesStatus_submitsToExecutor_andAttachesFuture() {
        Job job = pendingJob(202L, 9L);
        ActiveJobHandle handle = mock(202L, 9L);
        when(jobRepository.claimNextPending(anyInt())).thenReturn(List.of(job));
        when(registry.tryRegister(202L, 9L)).thenReturn(Optional.of(handle));
        Future<?> future = CompletableFuture.completedFuture(null);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> future);

        scheduler.poll();

        ArgumentCaptor<Job> savedCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(savedCaptor.capture());
        Job saved = savedCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(202L);
        assertThat(saved.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(saved.getClaimedByInstance()).isEqualTo("instance-A");
        assertThat(saved.getClaimedAt()).isNotNull();
        assertThat(saved.getStartedAt()).isNotNull();

        verify(broker).publishStatus(202L, 9L, JobStatus.RUNNING);

        verify(executor, times(1)).submit(any(Runnable.class));
        verify(registry).attachFuture(eq(202L), eq(future));
    }

    @Test
    void poll_dispatchesEachClaimedJob_inOrder() {
        Job j1 = pendingJob(1L, 100L);
        Job j2 = pendingJob(2L, 200L);
        when(jobRepository.claimNextPending(anyInt())).thenReturn(List.of(j1, j2));
        when(registry.tryRegister(1L, 100L)).thenReturn(Optional.of(mock(1L, 100L)));
        when(registry.tryRegister(2L, 200L)).thenReturn(Optional.of(mock(2L, 200L)));
        when(executor.submit(any(Runnable.class)))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(null));

        scheduler.poll();

        verify(jobRepository, times(2)).save(any(Job.class));
        verify(executor, times(2)).submit(any(Runnable.class));
        verify(registry).attachFuture(eq(1L), any());
        verify(registry).attachFuture(eq(2L), any());
    }

    @Test
    void poll_releasesRegistrySlot_whenExecutorRejectsSubmission() {
        Job job = pendingJob(303L, 11L);
        when(jobRepository.claimNextPending(anyInt())).thenReturn(List.of(job));
        when(registry.tryRegister(303L, 11L)).thenReturn(Optional.of(mock(303L, 11L)));
        when(executor.submit(any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));

        try {
            scheduler.poll();
        } catch (RejectedExecutionException ignored) {
            // implementation may rethrow; the contract under test is
            // that the registry slot is returned regardless
        }

        verify(registry).release(303L);
    }

    private static Job pendingJob(long id, long userId) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(userId);
        job.setStatus(JobStatus.PENDING);
        job.setProgress(0);
        job.setCancelRequested(false);
        job.setCreditsCost(1);
        job.setWidth(64);
        job.setHeight(64);
        job.setPrompt("p");
        job.setSeed(7L);
        return job;
    }

    private static ActiveJobHandle mock(long jobId, long userId) {
        return new ActiveJobHandle(jobId, userId, Instant.now());
    }
}
