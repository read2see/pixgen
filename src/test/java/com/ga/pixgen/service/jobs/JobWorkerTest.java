package com.ga.pixgen.service.jobs;

import com.ga.pixgen.dto.JobEventDto;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.service.images.GenerationRequest;
import com.ga.pixgen.service.images.ImageGenerator;
import com.ga.pixgen.service.images.LocalImageStorage;
import com.ga.pixgen.service.images.StoredImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
 *     <li>Take the per-user lock and delegate the four database
 *         mutations (credit deduction, image, metadata, status flip) to
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
        assertThat(request.seed()).isEqualTo(42L);
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

        ArgumentCaptor<JobEventDto> eventCaptor = ArgumentCaptor.forClass(JobEventDto.class);
        verify(broker, times(progressFired.size() + 1)).publish(eventCaptor.capture());
        List<Integer> publishedProgress = eventCaptor.getAllValues().stream()
                .filter(e -> JobEventDto.TYPE_PROGRESS.equals(e.type()))
                .map(JobEventDto::progress)
                .toList();
        assertThat(publishedProgress).containsExactly(0, 25, 50, 75, 100);
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

        ArgumentCaptor<JobEventDto> captor = ArgumentCaptor.forClass(JobEventDto.class);
        verify(broker, times(1)).publish(captor.capture());
        JobEventDto event = captor.getValue();
        assertThat(event.type()).isEqualTo(JobEventDto.TYPE_STATUS);
        assertThat(event.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(event.jobId()).isEqualTo(101L);
        assertThat(event.userId()).isEqualTo(7L);
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

    private static Job sampleJob() {
        Job job = new Job();
        job.setId(101L);
        job.setUserId(7L);
        job.setStatus(JobStatus.RUNNING);
        job.setPrompt("a cat");
        job.setNegativePrompt(null);
        job.setWidth(64);
        job.setHeight(32);
        job.setSteps(20);
        job.setCfgScale(7.5);
        job.setSeed(42L);
        job.setSampler("euler-a");
        job.setModelName("sd-1.5");
        job.setCreditsCost(1);
        job.setProgress(0);
        return job;
    }
}
