package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.exception.InsufficientCreditsException;
import com.ga.pixgen.exception.PendingJobLimitException;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
