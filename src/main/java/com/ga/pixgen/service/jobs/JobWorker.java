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
 * <p>Each invocation drives one {@link Job} from {@link JobStatus#RUNNING}
 * (set by the scheduler) to one of three terminal states:</p>
 * <ul>
 *     <li>{@link JobStatus#SUCCEEDED} — image produced, credits deducted,
 *         {@link com.ga.pixgen.model.Image Image} +
 *         {@link com.ga.pixgen.model.ImageMetadata ImageMetadata}
 *         persisted via {@link JobCompletionService}.</li>
 *     <li>{@link JobStatus#CANCELLED} — observed an interrupt or one of
 *         the cancel flags between progress ticks; the on-disk artifact
 *         (if any) is deleted before exit.</li>
 *     <li>{@link JobStatus#FAILED} — the generator raised, or the
 *         conditional credit {@code UPDATE} returned zero rows
 *         (rendered as the canonical {@code INSUFFICIENT_CREDITS}
 *         reason after the on-disk artifact is deleted).</li>
 * </ul>
 *
 * <p>The success-completion critical section runs inside a per-user
 * {@link java.util.concurrent.locks.ReentrantLock} (via
 * {@link UserJobLocks}) so two parallel jobs of the same user cannot
 * race the conditional credit {@code UPDATE} into a negative balance.
 * Atomicity of the four mutations themselves is provided by
 * {@link JobCompletionService}'s {@code @Transactional} method.</p>
 *
 * <p>Cancellation is honoured between progress ticks via three signals:
 * the worker thread's interrupt flag, the local {@link ActiveJobHandle}'s
 * {@code cancelRequested} flag (flipped by
 * {@link ActiveJobRegistry#requestCancel(Long)}), and the database
 * {@code cancel_requested} column (flipped by {@code JobService} when
 * the job is running on a different instance). Observing any of them
 * interrupts the worker thread so the generator's next sleep boundary
 * raises {@link InterruptedException} and the worker translates that
 * into {@code CANCELLED}.</p>
 */
@Component
public class JobWorker {

    private static final int DEFAULT_DIMENSION = 512;

    /** Reason string persisted on jobs failed because of credit shortage. */
    static final String INSUFFICIENT_CREDITS_REASON = "INSUFFICIENT_CREDITS";

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
        StoredImage stored = null;
        try {
            stored = generator.generate(buildRequest(job), buildProgressListener(jobId, userId));
            if (finalizeSuccess(job, stored)) {
                publish(JobEventDto.status(jobId, userId, JobStatus.SUCCEEDED));
            } else {
                handleInsufficientCredits(jobId, userId, stored);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleCancelled(jobId, userId, stored);
        } catch (RuntimeException e) {
            handleFailure(jobId, userId, stored, e);
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
            if (cancelObserved(jobId)) {
                // Flip the worker thread's interrupt flag so the generator
                // raises InterruptedException at its next sleep boundary
                // without inspecting any backend-specific state.
                Thread.currentThread().interrupt();
                return;
            }
            jobRepository.updateProgress(jobId, percent);
            publish(JobEventDto.progress(jobId, userId, percent));
        };
    }

    private boolean cancelObserved(Long jobId) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        boolean handleFlag = registry.get(jobId)
                .map(ActiveJobHandle::isCancelRequested)
                .orElse(false);
        if (handleFlag) {
            return true;
        }
        return jobRepository.findCancelRequested(jobId).orElse(false);
    }

    private boolean finalizeSuccess(Job job, StoredImage stored) {
        AtomicBoolean creditsDeducted = new AtomicBoolean(false);
        locks.withLock(job.getUserId(), () ->
                creditsDeducted.set(completionService.completeSuccess(job, stored)));
        return creditsDeducted.get();
    }

    private void handleInsufficientCredits(Long jobId, Long userId, StoredImage stored) {
        safelyDelete(stored);
        jobRepository.markFailed(jobId, INSUFFICIENT_CREDITS_REASON);
        publish(JobEventDto.status(jobId, userId, JobStatus.FAILED, INSUFFICIENT_CREDITS_REASON));
    }

    private void handleCancelled(Long jobId, Long userId, StoredImage stored) {
        safelyDelete(stored);
        jobRepository.markCancelled(jobId);
        publish(JobEventDto.status(jobId, userId, JobStatus.CANCELLED));
    }

    private void handleFailure(Long jobId, Long userId, StoredImage stored, RuntimeException cause) {
        safelyDelete(stored);
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        jobRepository.markFailed(jobId, message);
        publish(JobEventDto.status(jobId, userId, JobStatus.FAILED, message));
    }

    private void safelyDelete(StoredImage stored) {
        if (stored == null) {
            return;
        }
        try {
            storage.delete(stored.relativePath());
        } catch (RuntimeException ignored) {
            // Best-effort cleanup; never let a delete failure mask the
            // original cancellation or business failure.
        }
    }

    private void publish(JobEventDto event) {
        try {
            broker.publish(event);
        } catch (RuntimeException ignored) {
            // The broker is in-process and lock-free, but we still refuse
            // to let an SSE plumbing failure swallow the worker's
            // terminal status update.
        }
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
