package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.exception.InsufficientCreditsException;
import com.ga.pixgen.exception.JobNotCancellableException;
import com.ga.pixgen.exception.JobNotFoundException;
import com.ga.pixgen.exception.PendingJobLimitException;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.JobRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Application-facing entry point for job lifecycle operations.
 *
 * <p>This branch delivers the synchronous side of the API: submission,
 * lookup, listing and cancellation routing. The asynchronous side
 * (claiming and execution) lives in {@code JobScheduler} / {@code JobWorker}
 * and is wired in a later branch.</p>
 */
@Service
public class JobService {

    /**
     * Roles whose holders may read or cancel jobs they do not own. The
     * controller layer also gates these endpoints with {@code @PreAuthorize}
     * on the matching permissions; the service-side check enforces
     * ownership end-to-end so misuse from non-HTTP entry points (tests,
     * future internal callers) cannot bypass it.
     *
     * @param "ADMIN" the "admin" value
     * @param "MODERATOR" the "moderator" value
     * @return the static final Set<String> PRIVILEGED_ROLES = result
     */
    static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "MODERATOR");

    private final JobRepository jobRepository;
    private final ActiveJobRegistry activeJobRegistry;
    private final JobsProperties jobsProperties;

    public JobService(JobRepository jobRepository,
                      ActiveJobRegistry activeJobRegistry,
                      JobsProperties jobsProperties) {
        this.jobRepository = jobRepository;
        this.activeJobRegistry = activeJobRegistry;
        this.jobsProperties = jobsProperties;
    }

    /**
     * Persist a new {@link JobStatus#PENDING} job for {@code user}.
     *
     * <p>Two pre-flight checks run before any row is written:
     * <ol>
     *     <li>The number of {@code PENDING} jobs the user already has must
     *         be strictly below {@code app.jobs.max-pending-jobs-per-user};
     *         otherwise {@link PendingJobLimitException} is raised.</li>
     *     <li>The user's credit balance must be at least
     *         {@code app.jobs.credits-per-image}; otherwise
     *         {@link InsufficientCreditsException} is raised.</li>
     * </ol>
     * Credits are <em>not</em> deducted here — the worker's success path
     * performs the conditional {@code UPDATE users SET credits = credits - cost}
     * inside a per-user lock so concurrent jobs cannot drive a balance
     * negative.</p>
     */
    @Transactional
    public Job submit(User user, JobSubmission submission) {
        long pending = jobRepository.countByUserIdAndStatus(user.getId(), JobStatus.PENDING);
        if (pending >= jobsProperties.getMaxPendingJobsPerUser()) {
            throw new PendingJobLimitException(jobsProperties.getMaxPendingJobsPerUser());
        }
        int cost = jobsProperties.getCreditsPerImage();
        Integer balance = user.getCredits();
        int available = balance == null ? 0 : balance;
        if (available < cost) {
            throw new InsufficientCreditsException(cost, available);
        }

        Job job = new Job();
        job.setUserId(user.getId());
        job.setStatus(JobStatus.PENDING);
        job.setProgress(0);
        job.setCancelRequested(false);
        job.setCreditsCost(cost);
        job.setPrompt(submission.prompt());
        job.setNegativePrompt(submission.negativePrompt());
        job.setWidth(submission.width());
        job.setHeight(submission.height());
        job.setSteps(submission.steps());
        job.setCfgScale(submission.cfgScale());
        job.setSeed(submission.seed());
        job.setSampler(submission.sampler());
        job.setModelName(submission.modelName());
        return jobRepository.save(job);
    }

    /**
     * Look up a job by id, enforcing that {@code actor} either owns the
     * job or holds a {@linkplain #PRIVILEGED_ROLES privileged} role.
     *
     * @throws JobNotFoundException if no job with {@code jobId} exists
     * @throws AccessDeniedException if {@code actor} is neither owner nor privileged
     */
    @Transactional(readOnly = true)
    public Job get(Long jobId, User actor) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (!canAccess(actor, job)) {
            throw new AccessDeniedException("Not allowed to access job " + jobId);
        }
        return job;
    }

    /**
     * List jobs owned by {@code actor}, optionally filtered by status,
     * ordered most-recent first.
     */
    @Transactional(readOnly = true)
    public List<Job> listMine(User actor, JobStatus status) {
        if (status == null) {
            return jobRepository.findByUserIdOrderByCreatedAtDesc(actor.getId());
        }
        return jobRepository.findByUserIdAndStatusOrderByCreatedAtDesc(actor.getId(), status);
    }

    /**
     * Cancel {@code jobId} on behalf of {@code actor}, dispatching to the
     * right primitive depending on where the job currently lives:
     *
     * <ul>
     *     <li>{@link JobStatus#PENDING}: a single conditional
     *         {@code UPDATE … WHERE status='PENDING'}. If the row count
     *         is {@code 0} the poller raced us and the job is no longer
     *         cancellable from this path.</li>
     *     <li>{@link JobStatus#RUNNING} on this JVM: route through
     *         {@link ActiveJobRegistry#requestCancel(Long)} which flips
     *         the volatile flag and interrupts the worker future.</li>
     *     <li>{@link JobStatus#RUNNING} on another JVM: persist
     *         {@code cancel_requested=true} in the database; the owning
     *         instance's worker will observe the flag on its next
     *         progress tick.</li>
     *     <li>Any terminal state: {@link JobNotCancellableException}.</li>
     * </ul>
     *
     * @throws JobNotFoundException if no job with {@code jobId} exists
     * @throws AccessDeniedException if {@code actor} is neither owner nor privileged
     * @throws JobNotCancellableException if the job is already terminal
     *         or the conditional update lost the race against the poller
     */
    @Transactional
    public void cancel(Long jobId, User actor) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (!canAccess(actor, job)) {
            throw new AccessDeniedException("Not allowed to cancel job " + jobId);
        }
        switch (job.getStatus()) {
            case PENDING -> {
                int updated = jobRepository.markCancelledIfPending(jobId);
                if (updated == 0) {
                    throw new JobNotCancellableException(jobId);
                }
            }
            case RUNNING -> {
                boolean handledLocally = activeJobRegistry.requestCancel(jobId);
                if (!handledLocally) {
                    jobRepository.markCancelRequestedIfRunning(jobId);
                }
            }
            case SUCCEEDED, FAILED, CANCELLED ->
                    throw new JobNotCancellableException(jobId, job.getStatus());
        }
    }

    /**
     * Return the 1-indexed position of {@code job} in the global pending
     * queue, or {@code null} if the job is not currently {@link JobStatus#PENDING}.
     * The query relies on monotonic job ids so the count of pending rows
     * with {@code id <= job.getId()} is the job's place in line.
     */
    @Transactional(readOnly = true)
    public Integer queuePosition(Job job) {
        if (job == null || job.getStatus() != JobStatus.PENDING || job.getId() == null) {
            return null;
        }
        long position = jobRepository.countPendingNotAfter(job.getId());
        return position <= 0 ? null : (int) position;
    }

    private static boolean canAccess(User actor, Job job) {
        if (actor == null) {
            return false;
        }
        if (actor.getId() != null && actor.getId().equals(job.getUserId())) {
            return true;
        }
        Role role = actor.getRole();
        return role != null && PRIVILEGED_ROLES.contains(role.getName());
    }
}
