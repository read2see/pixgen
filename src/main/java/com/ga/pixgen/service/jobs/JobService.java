package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.exception.InsufficientCreditsException;
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
     * <li>The number of {@code PENDING} jobs the user already has must
     * be strictly below {@code app.jobs.max-pending-jobs-per-user};
     * otherwise {@link PendingJobLimitException} is raised.</li>
     * <li>The user's credit balance must be at least
     * {@code app.jobs.credits-per-image}; otherwise
     * {@link InsufficientCreditsException} is raised.</li>
     * </ol>
     * Credits are <em>not</em> deducted here — the worker's success path
     * performs the conditional {@code UPDATE users SET credits = credits - cost}
     * inside a per-user lock so concurrent jobs cannot drive a balance
     * negative.</p>
     *
     * @param user the user value
     * @param submission the submission value
     * @return the Job result
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
     * @param jobId the job id value
     * @param actor the actor value
     * @return the Job result
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
     *
     * @param actor the actor value
     * @param status the status value
     * @return the matching rows, which may be empty
     */
    @Transactional(readOnly = true)
    public List<Job> listMine(User actor, JobStatus status) {
        if (status == null) {
            return jobRepository.findByUserIdOrderByCreatedAtDesc(actor.getId());
        }
        return jobRepository.findByUserIdAndStatusOrderByCreatedAtDesc(actor.getId(), status);
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
