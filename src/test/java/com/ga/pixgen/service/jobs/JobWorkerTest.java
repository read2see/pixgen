package com.ga.pixgen.service.jobs;

import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.service.images.GenerationRequest;
import com.ga.pixgen.service.images.ImageGenerator;
import com.ga.pixgen.service.images.LocalImageStorage;
import com.ga.pixgen.service.images.StoredImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Success-path tests for {@link JobWorker}, the runnable submitted to the
 * worker pool by {@link JobScheduler}.
 *
 * <p>These tests pin the happy-path behaviour the plan requires:</p>
 * <ul>
 *     <li>Translate the {@link Job} into a {@link GenerationRequest} for
 *         the {@link ImageGenerator}.</li>
 *     <li>Forward every progress callback to the {@link JobEventBroker}
 *         and persist it on the {@code progress} column so cross-instance
 *         pollers can see lifecycle progress without reading SSE.</li>
 *     <li>Take the per-user lock and delegate the database mutations
 *         (credit deduction, image row, status flip) to
 *         {@link JobCompletionService} so they run atomically inside a
 *         single Spring-managed transaction.</li>
 *     <li>Emit a {@code SUCCEEDED} status event when
 *         {@link JobCompletionService} reports the credit deduction
 *         succeeded.</li>
 *     <li>Release the {@link ActiveJobRegistry} slot in a {@code finally}
 *         block so the semaphore and per-user counters never leak.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobWorkerTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobCompletionService completionService;

    @Mock
    private ImageGenerator generator;

    @Mock
    private LocalImageStorage storage;

    @Mock
    private ActiveJobRegistry registry;

    @Mock
    private JobEventBroker broker;

    private UserJobLocks locks;
    private JobWorker worker;

    @BeforeEach
    void setUp() {
        locks = new UserJobLocks();
        worker = new JobWorker(
                jobRepository,
                completionService,
                generator,
                storage,
                registry,
                locks,
                broker);
    }

    @AfterEach
    void tearDown() {
        // The worker re-asserts the interrupt flag in its cancellation path;
        // clear it so subsequent tests start with a clean thread state.
        Thread.interrupted();
    }

    @Test
    void execute_invokesGenerator_withRequestBuiltFromJob() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/abc.png", 1234L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(true);

        worker.execute(job);

        ArgumentCaptor<GenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationRequest.class);
        verify(generator).generate(requestCaptor.capture(), any());
        GenerationRequest request = requestCaptor.getValue();
        assertThat(request.jobId()).isEqualTo(101L);
        assertThat(request.userId()).isEqualTo(7L);
        assertThat(request.width()).isEqualTo(64);
        assertThat(request.height()).isEqualTo(32);
        assertThat(request.prompt()).isEqualTo("a cat");
        assertThat(request.negativePrompt()).isNull();
        assertThat(request.numInferenceSteps()).isEqualTo(20);
        assertThat(request.guidanceScale()).isEqualTo(7.5);
        assertThat(request.seed()).isEqualTo(42L);
        assertThat(request.sampler()).isEqualTo("euler-a");
        assertThat(request.modelId()).isEqualTo("runwayml/stable-diffusion-v1-5");
    }

    @Test
    void execute_forwardsProgressToBroker_andUpdatesProgressColumn() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/abc.png", 1L, 64, 32, "image/png");
        List<Integer> progressFired = new ArrayList<>();
        when(generator.generate(any(GenerationRequest.class), any())).thenAnswer(invocation -> {
            IntConsumer listener = invocation.getArgument(1);
            for (int p : new int[]{0, 25, 50, 75, 100}) {
                listener.accept(p);
                progressFired.add(p);
            }
            return stored;
        });
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(true);

        worker.execute(job);

        ArgumentCaptor<Integer> percentCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(broker, times(progressFired.size()))
                .publishProgress(eq(101L), eq(7L), percentCaptor.capture());
        assertThat(percentCaptor.getAllValues()).containsExactly(0, 25, 50, 75, 100);
        verify(broker).publishStatus(101L, 7L, JobStatus.SUCCEEDED, null);
        verify(jobRepository).updateProgress(101L, 0);
        verify(jobRepository).updateProgress(101L, 25);
        verify(jobRepository).updateProgress(101L, 50);
        verify(jobRepository).updateProgress(101L, 75);
        verify(jobRepository).updateProgress(101L, 100);
    }

    @Test
    void execute_delegatesSuccessCompletionToCompletionService() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/cafe.png", 9876L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(eq(job), eq(stored))).thenReturn(true);

        worker.execute(job);

        verify(completionService).completeSuccess(job, stored);
    }

    @Test
    void execute_publishesSucceededStatusEvent_whenCompletionReturnsTrue() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/done.png", 1L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(true);

        worker.execute(job);

        verify(broker, times(1)).publishStatus(101L, 7L, JobStatus.SUCCEEDED, null);
        verify(broker, never()).publishProgress(anyLong(), anyLong(), anyInt());
    }

    @Test
    void execute_releasesRegistrySlot_onSuccess() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/done.png", 1L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(true);

        worker.execute(job);

        verify(registry).release(101L);
    }

    @Test
    void execute_doesNotDeleteFile_onHappyPath() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/keep.png", 1L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(true);

        worker.execute(job);

        verify(storage, never()).delete(any());
    }

    @Test
    void execute_marksCancelledAndDoesNotPersistImage_whenGeneratorThrowsInterrupted() throws Exception {
        Job job = sampleJob();
        when(generator.generate(any(GenerationRequest.class), any()))
                .thenAnswer(invocation -> { throw new InterruptedException("user cancel"); });

        worker.execute(job);

        verify(jobRepository).markCancelled(101L);
        verify(jobRepository, never()).markSucceeded(anyLong());
        verify(jobRepository, never()).markFailed(anyLong(), any());
        verify(completionService, never()).completeSuccess(any(), any());
        verify(storage, never()).delete(any());
        // The interrupt flag must be re-asserted so the executor sees it.
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void execute_publishesCancelledStatusEvent_whenInterrupted() throws Exception {
        Job job = sampleJob();
        when(generator.generate(any(GenerationRequest.class), any()))
                .thenAnswer(invocation -> { throw new InterruptedException("cancel"); });

        worker.execute(job);

        verify(broker).publishStatus(101L, 7L, JobStatus.CANCELLED, null);
    }

    @Test
    void execute_marksFailed_whenGeneratorThrowsRuntimeException() throws Exception {
        Job job = sampleJob();
        when(generator.generate(any(GenerationRequest.class), any()))
                .thenThrow(new RuntimeException("model exploded"));

        worker.execute(job);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobRepository).markFailed(eq(101L), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains("model exploded");
        verify(jobRepository, never()).markSucceeded(anyLong());
        verify(jobRepository, never()).markCancelled(anyLong());
        // Generator threw before producing a stored image — nothing to delete.
        verify(storage, never()).delete(any());
    }

    @Test
    void execute_publishesFailedStatusEvent_onRuntimeException() throws Exception {
        Job job = sampleJob();
        when(generator.generate(any(GenerationRequest.class), any()))
                .thenThrow(new RuntimeException("disk full"));

        worker.execute(job);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(broker).publishStatus(eq(101L), eq(7L), eq(JobStatus.FAILED), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains("disk full");
    }

    @Test
    void execute_marksFailedAndDeletesFile_whenInsufficientCredits() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/orphan.png", 1L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(false);

        worker.execute(job);

        verify(jobRepository).markFailed(eq(101L), contains("INSUFFICIENT_CREDITS"));
        verify(jobRepository, never()).markSucceeded(anyLong());
        verify(storage).delete("7/orphan.png");
    }

    @Test
    void execute_publishesFailedStatus_whenInsufficientCredits() throws Exception {
        Job job = sampleJob();
        StoredImage stored = new StoredImage("7/orphan.png", 1L, 64, 32, "image/png");
        when(generator.generate(any(GenerationRequest.class), any())).thenReturn(stored);
        when(completionService.completeSuccess(any(Job.class), any(StoredImage.class))).thenReturn(false);

        worker.execute(job);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(broker).publishStatus(eq(101L), eq(7L), eq(JobStatus.FAILED), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains("INSUFFICIENT_CREDITS");
    }

    @Test
    void execute_releasesRegistrySlot_evenWhenGeneratorThrows() throws Exception {
        Job job = sampleJob();
        when(generator.generate(any(GenerationRequest.class), any()))
                .thenThrow(new RuntimeException("boom"));

        worker.execute(job);

        verify(registry).release(101L);
    }

    @Test
    void execute_releasesRegistrySlot_evenWhenGeneratorIsCancelled() throws Exception {
        Job job = sampleJob();
        when(generator.generate(any(GenerationRequest.class), any()))
                .thenAnswer(invocation -> { throw new InterruptedException(); });

        worker.execute(job);

        verify(registry).release(101L);
    }

    @Test
    void execute_observesDbCancelFlagBetweenProgressTicks_andTransitionsToCancelled() throws Exception {
        Job job = sampleJob();
        when(jobRepository.findCancelRequested(101L))
                .thenReturn(Optional.of(false))
                .thenReturn(Optional.of(true));
        when(generator.generate(any(GenerationRequest.class), any())).thenAnswer(invocation -> {
            IntConsumer listener = invocation.getArgument(1);
            listener.accept(25);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancel observed");
            }
            listener.accept(50);
            // Simulates the generator picking up the interrupt at its next sleep boundary.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancel observed");
            }
            return new StoredImage("7/should-not-happen.png", 1L, 64, 32, "image/png");
        });

        worker.execute(job);

        verify(jobRepository, times(2)).findCancelRequested(101L);
        verify(jobRepository).markCancelled(101L);
        verify(jobRepository, never()).markSucceeded(anyLong());
        // Progress was published only for the first, pre-cancel tick.
        verify(jobRepository).updateProgress(101L, 25);
        verify(jobRepository, never()).updateProgress(eq(101L), eq(50));
    }

    @Test
    void execute_observesHandleCancelFlagBetweenProgressTicks_andTransitionsToCancelled() throws Exception {
        Job job = sampleJob();
        ActiveJobHandle cancelledHandle = new ActiveJobHandle(101L, 7L, Instant.now());
        cancelledHandle.markCancelRequested();
        when(registry.get(101L)).thenReturn(Optional.of(cancelledHandle));
        when(generator.generate(any(GenerationRequest.class), any())).thenAnswer(invocation -> {
            IntConsumer listener = invocation.getArgument(1);
            listener.accept(40);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancel via handle");
            }
            return new StoredImage("7/should-not-happen.png", 1L, 64, 32, "image/png");
        });

        worker.execute(job);

        verify(jobRepository).markCancelled(101L);
        verify(jobRepository, never()).markSucceeded(anyLong());
        verify(jobRepository, never()).updateProgress(eq(101L), eq(40));
    }

    private static Job sampleJob() {
        Job job = new Job();
        job.setId(101L);
        job.setUserId(7L);
        job.setStatus(JobStatus.RUNNING);
        job.setPrompt("a cat");
        job.setNegativePrompt(null);
        job.setWidth(64);
        job.setHeight(32);
        job.setNumInferenceSteps(20);
        job.setGuidanceScale(7.5);
        job.setSeed(42L);
        job.setSampler("euler-a");
        job.setModelId("runwayml/stable-diffusion-v1-5");
        job.setCreditsCost(1);
        job.setProgress(0);
        return job;
    }
}
