package com.ga.pixgen.service.jobs;

import com.ga.pixgen.config.JobsProperties;
import com.ga.pixgen.config.GenerationModelsProperties;
import com.ga.pixgen.exception.InsufficientCreditsException;
import com.ga.pixgen.exception.JobNotCancellableException;
import com.ga.pixgen.exception.JobNotFoundException;
import com.ga.pixgen.exception.PendingJobLimitException;
import com.ga.pixgen.exception.UnknownGenerationModelException;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.service.generation.GenerationModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>Covers pre-flight checks before a {@link Job} row is persisted: pending
 * ceiling, credit balance, and known {@code MODEL_ID}.</p>
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ActiveJobRegistry activeJobRegistry;

    private JobsProperties jobsProperties;
    private GenerationModelCatalog catalog;

    private JobService jobService;

    private User user;

    @BeforeEach
    void setUp() {
        jobsProperties = new JobsProperties();
        jobsProperties.setMaxPendingJobsPerUser(10);
        jobsProperties.setCreditsPerImage(1);

        GenerationModelsProperties modelProps = new GenerationModelsProperties();
        GenerationModelsProperties.ModelEntry entry = new GenerationModelsProperties.ModelEntry();
        entry.setModelId("runwayml/stable-diffusion-v1-5");
        entry.setLabel("SD 1.5");
        modelProps.setModels(java.util.List.of(entry));
        catalog = new GenerationModelCatalog(modelProps);

        jobService = new JobService(jobRepository, activeJobRegistry, jobsProperties, catalog);

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
                "runwayml/stable-diffusion-v1-5"
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
        assertThat(persisted.getNumInferenceSteps()).isEqualTo(30);
        assertThat(persisted.getGuidanceScale()).isEqualTo(7.5);
        assertThat(persisted.getSeed()).isEqualTo(42L);
        assertThat(persisted.getSampler()).isEqualTo("euler-a");
        assertThat(persisted.getModelId()).isEqualTo("runwayml/stable-diffusion-v1-5");
        assertThat(persisted.getCreditsCost()).isEqualTo(jobsProperties.getCreditsPerImage());
        assertThat(persisted.getProgress()).isZero();
        assertThat(persisted.isCancelRequested()).isFalse();
        assertThat(saved.getId()).isEqualTo(123L);
    }

    @Test
    void submit_throwsUnknownModel_whenModelIdNotInCatalog() {
        when(jobRepository.countByUserIdAndStatus(7L, JobStatus.PENDING)).thenReturn(0L);

        JobSubmission submission = new JobSubmission(
                "anything", null, null, null, null, null, null, null, "unknown/model");

        assertThatThrownBy(() -> jobService.submit(user, submission))
                .isInstanceOf(UnknownGenerationModelException.class);

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void submit_throwsPendingJobLimit_whenAtCeiling() {
        jobsProperties.setMaxPendingJobsPerUser(3);
        when(jobRepository.countByUserIdAndStatus(7L, JobStatus.PENDING)).thenReturn(3L);

        JobSubmission submission = new JobSubmission(
                "anything", null, null, null, null, null, null, null,
                "runwayml/stable-diffusion-v1-5");

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
                "anything", null, null, null, null, null, null, null,
                "runwayml/stable-diffusion-v1-5");

        assertThatThrownBy(() -> jobService.submit(user, submission))
                .isInstanceOf(InsufficientCreditsException.class);

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void get_returnsJob_whenActorIsOwner() {
        Job job = newJob(99L, 7L, JobStatus.PENDING);
        when(jobRepository.findById(99L)).thenReturn(Optional.of(job));

        Job result = jobService.get(99L, user);

        assertThat(result).isSameAs(job);
    }

    @Test
    void get_returnsJob_whenActorIsAdmin() {
        Role adminRole = new Role();
        adminRole.setName("ADMIN");
        User admin = new User();
        admin.setId(1L);
        admin.setRole(adminRole);
        Job job = newJob(99L, 999L, JobStatus.RUNNING);
        when(jobRepository.findById(99L)).thenReturn(Optional.of(job));

        Job result = jobService.get(99L, admin);

        assertThat(result).isSameAs(job);
    }

    @Test
    void get_returnsJob_whenActorIsModerator() {
        Role moderatorRole = new Role();
        moderatorRole.setName("MODERATOR");
        User moderator = new User();
        moderator.setId(2L);
        moderator.setRole(moderatorRole);
        Job job = newJob(99L, 999L, JobStatus.RUNNING);
        when(jobRepository.findById(99L)).thenReturn(Optional.of(job));

        Job result = jobService.get(99L, moderator);

        assertThat(result).isSameAs(job);
    }

    @Test
    void get_throwsAccessDenied_whenActorIsNeitherOwnerNorPrivileged() {
        User other = new User();
        other.setId(8L);
        Role otherRole = new Role();
        otherRole.setName("USER");
        other.setRole(otherRole);
        Job job = newJob(99L, 7L, JobStatus.PENDING);
        when(jobRepository.findById(99L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.get(99L, other))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void get_throwsJobNotFound_whenMissing() {
        when(jobRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.get(404L, user))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void listMine_returnsJobsForUser_inMostRecentFirstOrder() {
        Job j1 = newJob(1L, 7L, JobStatus.SUCCEEDED);
        Job j2 = newJob(2L, 7L, JobStatus.PENDING);
        when(jobRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(j2, j1));

        List<Job> jobs = jobService.listMine(user, null);

        assertThat(jobs).containsExactly(j2, j1);
        verify(jobRepository, never()).findByUserIdAndStatusOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void listMine_filtersByStatus_whenStatusProvided() {
        Job pending = newJob(2L, 7L, JobStatus.PENDING);
        when(jobRepository.findByUserIdAndStatusOrderByCreatedAtDesc(7L, JobStatus.PENDING))
                .thenReturn(List.of(pending));

        List<Job> jobs = jobService.listMine(user, JobStatus.PENDING);

        assertThat(jobs).containsExactly(pending);
        verify(jobRepository, never()).findByUserIdOrderByCreatedAtDesc(any());
    }

    @Test
    void cancel_pending_runsConditionalUpdate_andDoesNotTouchRegistry() {
        Job pending = newJob(50L, 7L, JobStatus.PENDING);
        when(jobRepository.findById(50L)).thenReturn(Optional.of(pending));
        when(jobRepository.markCancelledIfPending(50L)).thenReturn(1);

        jobService.cancel(50L, user);

        verify(jobRepository).markCancelledIfPending(50L);
        verify(activeJobRegistry, never()).requestCancel(any());
        verify(jobRepository, never()).markCancelRequestedIfRunning(any());
    }

    @Test
    void cancel_pending_throwsNotCancellable_whenConditionalUpdateRacedToZeroRows() {
        Job pending = newJob(50L, 7L, JobStatus.PENDING);
        when(jobRepository.findById(50L)).thenReturn(Optional.of(pending));
        when(jobRepository.markCancelledIfPending(50L)).thenReturn(0);

        assertThatThrownBy(() -> jobService.cancel(50L, user))
                .isInstanceOf(JobNotCancellableException.class);
    }

    @Test
    void cancel_running_onThisInstance_routesThroughRegistryWithoutDbFlag() {
        Job running = newJob(60L, 7L, JobStatus.RUNNING);
        when(jobRepository.findById(60L)).thenReturn(Optional.of(running));
        when(activeJobRegistry.requestCancel(60L)).thenReturn(true);

        jobService.cancel(60L, user);

        verify(activeJobRegistry).requestCancel(60L);
        verify(jobRepository, never()).markCancelRequestedIfRunning(any());
        verify(jobRepository, never()).markCancelledIfPending(any());
    }

    @Test
    void cancel_running_onAnotherInstance_setsDbCancelRequestedFlag() {
        Job running = newJob(70L, 7L, JobStatus.RUNNING);
        when(jobRepository.findById(70L)).thenReturn(Optional.of(running));
        when(activeJobRegistry.requestCancel(70L)).thenReturn(false);

        jobService.cancel(70L, user);

        verify(activeJobRegistry).requestCancel(70L);
        verify(jobRepository).markCancelRequestedIfRunning(70L);
    }

    @Test
    void cancel_throwsJobNotFound_whenMissing() {
        when(jobRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.cancel(404L, user))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void cancel_throwsAccessDenied_whenActorIsNeitherOwnerNorPrivileged() {
        User other = new User();
        other.setId(8L);
        Role userRole = new Role();
        userRole.setName("USER");
        other.setRole(userRole);
        Job pending = newJob(80L, 7L, JobStatus.PENDING);
        when(jobRepository.findById(80L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> jobService.cancel(80L, other))
                .isInstanceOf(AccessDeniedException.class);

        verify(jobRepository, never()).markCancelledIfPending(any());
        verify(activeJobRegistry, never()).requestCancel(any());
    }

    @Test
    void cancel_throwsNotCancellable_whenJobAlreadyTerminal() {
        for (JobStatus terminal : List.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED)) {
            Job job = newJob(90L, 7L, terminal);
            when(jobRepository.findById(90L)).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> jobService.cancel(90L, user))
                    .as("status=%s", terminal)
                    .isInstanceOf(JobNotCancellableException.class);
        }

        verify(jobRepository, never()).markCancelledIfPending(any());
        verify(activeJobRegistry, never()).requestCancel(any());
    }

    @Test
    void cancel_admin_canCancelAnotherUsersPendingJob() {
        Role adminRole = new Role();
        adminRole.setName("ADMIN");
        User admin = new User();
        admin.setId(1L);
        admin.setRole(adminRole);
        Job pending = newJob(91L, 7L, JobStatus.PENDING);
        when(jobRepository.findById(91L)).thenReturn(Optional.of(pending));
        when(jobRepository.markCancelledIfPending(91L)).thenReturn(1);

        jobService.cancel(91L, admin);

        verify(jobRepository).markCancelledIfPending(91L);
    }

    private static Job newJob(long id, long userId, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(userId);
        job.setStatus(status);
        return job;
    }
}
