package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.exception.InsufficientCreditsException;
import com.ga.pixgen.exception.PendingJobLimitException;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Submit-path tests for {@link JobService}. Covers the two pre-flight
 * validations the plan calls out for {@code POST /api/jobs}:
 * <ul>
 *     <li>Per-user pending-job ceiling
 *         ({@code app.jobs.max-pending-jobs-per-user}).</li>
 *     <li>Credit balance check against
 *         {@code app.jobs.credits-per-image}.</li>
 * </ul>
 * Both must run <em>before</em> the {@link Job} is persisted so failures
 * never leak rows and never burn credits.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ActiveJobRegistry activeJobRegistry;

    private JobsProperties jobsProperties;

    @InjectMocks
    private JobService jobService;

    private User user;

    @BeforeEach
    void setUp() {
        jobsProperties = new JobsProperties();
        jobsProperties.setMaxPendingJobsPerUser(10);
        jobsProperties.setCreditsPerImage(1);
        jobService = new JobService(jobRepository, activeJobRegistry, jobsProperties);

        Role role = new Role();
        role.setId(2L);
        role.setName("USER");

        user = new User();
        user.setId(7L);
        user.setEmail("alice@example.com");
        user.setCredits(5);
        user.setRole(role);
    }

    @Test
    void submit_persistsPendingJob_withCreditCostAndUserId() {
        when(jobRepository.countByUserIdAndStatus(7L, JobStatus.PENDING)).thenReturn(0L);
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job arg = invocation.getArgument(0);
            arg.setId(123L);
            return arg;
        });

        JobSubmission submission = new JobSubmission(
                "a cyberpunk fox",
                "blurry",
                512,
                512,
                30,
                7.5,
                42L,
                "euler-a",
                "sd-1.5"
        );

        Job saved = jobService.submit(user, submission);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job persisted = captor.getValue();

        assertThat(persisted.getUserId()).isEqualTo(7L);
        assertThat(persisted.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(persisted.getPrompt()).isEqualTo("a cyberpunk fox");
        assertThat(persisted.getNegativePrompt()).isEqualTo("blurry");
        assertThat(persisted.getWidth()).isEqualTo(512);
        assertThat(persisted.getHeight()).isEqualTo(512);
        assertThat(persisted.getSteps()).isEqualTo(30);
        assertThat(persisted.getCfgScale()).isEqualTo(7.5);
        assertThat(persisted.getSeed()).isEqualTo(42L);
        assertThat(persisted.getSampler()).isEqualTo("euler-a");
        assertThat(persisted.getModelName()).isEqualTo("sd-1.5");
        assertThat(persisted.getCreditsCost()).isEqualTo(jobsProperties.getCreditsPerImage());
        assertThat(persisted.getProgress()).isZero();
        assertThat(persisted.isCancelRequested()).isFalse();
        assertThat(saved.getId()).isEqualTo(123L);
    }

    @Test
    void submit_throwsPendingJobLimit_whenAtCeiling() {
        jobsProperties.setMaxPendingJobsPerUser(3);
        when(jobRepository.countByUserIdAndStatus(7L, JobStatus.PENDING)).thenReturn(3L);

        JobSubmission submission = new JobSubmission(
                "anything", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> jobService.submit(user, submission))
                .isInstanceOf(PendingJobLimitException.class);

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void submit_throwsInsufficientCredits_whenBalanceBelowCost() {
        user.setCredits(0);
        jobsProperties.setCreditsPerImage(1);
        when(jobRepository.countByUserIdAndStatus(7L, JobStatus.PENDING)).thenReturn(0L);

        JobSubmission submission = new JobSubmission(
                "anything", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> jobService.submit(user, submission))
                .isInstanceOf(InsufficientCreditsException.class);

        verify(jobRepository, never()).save(any(Job.class));
    }
}
