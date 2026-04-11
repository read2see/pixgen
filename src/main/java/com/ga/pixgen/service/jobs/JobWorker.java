package com.ga.pixgen.service.jobs;

import com.ga.pixgen.dto.JobEventDto;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.service.images.GenerationRequest;
import com.ga.pixgen.service.images.ImageGenerator;
import com.ga.pixgen.service.images.LocalImageStorage;
import com.ga.pixgen.service.images.StoredImage;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Single-job execution unit submitted to {@code jobWorkerExecutor} by
 * {@link JobScheduler}.
 *
 * <p>Each invocation drives one {@link Job} that the scheduler has
 * already flipped to {@link JobStatus#RUNNING} through the success
 * path:</p>
 * <ol>
 *     <li>Translate the job into a {@link GenerationRequest} and ask
 *         the {@link ImageGenerator} for an image, forwarding every
 *         progress callback to the {@link JobEventBroker} and the
 *         {@code progress} column.</li>
 *     <li>Take the per-user {@link UserJobLocks lock} and delegate the
 *         credit deduction, image / metadata persistence and status
 *         flip to {@link JobCompletionService} so the four mutations
 *         run inside a single Spring-managed transaction.</li>
 *     <li>Emit a {@code SUCCEEDED} status event so SSE clients
 *         transition the UI state.</li>
 * </ol>
 *
 * <p>This commit lands the success path. Cancellation, runtime-failure
 * handling, and {@code INSUFFICIENT_CREDITS} accounting follow in the
 * next commit alongside their dedicated tests.</p>
 */
@Component
public class JobWorker {

    private static final int DEFAULT_DIMENSION = 512;

    private final JobRepository jobRepository;
    private final JobCompletionService completionService;
    private final ImageGenerator generator;
    private final LocalImageStorage storage;
    private final ActiveJobRegistry registry;
    private final UserJobLocks locks;
    private final JobEventBroker broker;

    public JobWorker(JobRepository jobRepository,
                     JobCompletionService completionService,
                     ImageGenerator generator,
                     LocalImageStorage storage,
                     ActiveJobRegistry registry,
                     UserJobLocks locks,
                     JobEventBroker broker) {
        this.jobRepository = jobRepository;
        this.completionService = completionService;
        this.generator = generator;
        this.storage = storage;
        this.registry = registry;
        this.locks = locks;
        this.broker = broker;
    }

    public void execute(Job job) {
        Long jobId = job.getId();
        Long userId = job.getUserId();
        try {
            StoredImage stored = generator.generate(buildRequest(job),
                    buildProgressListener(jobId, userId));
            if (finalizeSuccess(job, stored)) {
                broker.publish(JobEventDto.status(jobId, userId, JobStatus.SUCCEEDED));
            }
        } catch (InterruptedException e) {
            // Full cancellation handling lands in the next commit; for now
            // restore the interrupt flag so callers still observe it.
            Thread.currentThread().interrupt();
        } finally {
            registry.release(jobId);
        }
    }

    private GenerationRequest buildRequest(Job job) {
        return new GenerationRequest(
                job.getId(),
                job.getUserId(),
                orDefault(job.getWidth(), DEFAULT_DIMENSION),
                orDefault(job.getHeight(), DEFAULT_DIMENSION),
                job.getPrompt(),
                job.getSeed());
    }

    private IntConsumer buildProgressListener(Long jobId, Long userId) {
        return percent -> {
            jobRepository.updateProgress(jobId, percent);
            broker.publish(JobEventDto.progress(jobId, userId, percent));
        };
    }

    private boolean finalizeSuccess(Job job, StoredImage stored) {
        AtomicBoolean creditsDeducted = new AtomicBoolean(false);
        locks.withLock(job.getUserId(), () ->
                creditsDeducted.set(completionService.completeSuccess(job, stored)));
        return creditsDeducted.get();
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
