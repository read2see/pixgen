package com.ga.pixgen.repository;

import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the startup-recovery query that requeues {@code RUNNING} jobs
 * abandoned by this instance back to {@code PENDING}. Per the phase 2 plan a
 * crashed JVM leaves rows tagged with its {@code claimed_by_instance}
 * identifier; the next time the same instance starts up it must reclaim
 * those rows, drop the instance tag and let the scheduler re-dispatch them.
 *
 * <p>The behavioural contract enforced here:</p>
 * <ul>
 *   <li>Only {@code RUNNING} rows are touched (other statuses are inert).</li>
 *   <li>Only rows whose {@code claimed_by_instance} equals the supplied
 *       identifier are touched — work owned by a different live instance is
 *       left alone so two pollers cannot fight over the same job.</li>
 *   <li>The {@code claimed_by_instance} column is cleared to {@code NULL}
 *       so the next claim cycle treats the row as fresh.</li>
 *   <li>The query returns the number of rows it reset for tests/log lines.</li>
 * </ul>
 */
@Transactional
class JobRepositoryStartupRecoveryTest extends AbstractPostgresContainerTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void cleanState() {
        em.getEntityManager()
                .createQuery("DELETE FROM Job")
                .executeUpdate();
    }

    @Test
    void requeuesOnlyRunningRowsOwnedByThisInstance() {
        Job mineRunning1 = persistRunning(1L, "instance-A");
        Job mineRunning2 = persistRunning(2L, "instance-A");
        Job otherRunning = persistRunning(3L, "instance-B");
        Job pendingMine = persistPending(4L, "instance-A");
        Job succeededMine = persistTerminal(5L, JobStatus.SUCCEEDED, "instance-A");
        Job failedMine = persistTerminal(6L, JobStatus.FAILED, "instance-A");
        Job cancelledMine = persistTerminal(7L, JobStatus.CANCELLED, "instance-A");

        int requeued = jobRepository.requeueRunningOwnedBy("instance-A");

        assertThat(requeued).isEqualTo(2);

        List<Job> reloaded = jobRepository.findAllById(List.of(
                mineRunning1.getId(),
                mineRunning2.getId(),
                otherRunning.getId(),
                pendingMine.getId(),
                succeededMine.getId(),
                failedMine.getId(),
                cancelledMine.getId()));

        assertThat(reloaded).filteredOn(j -> j.getId().equals(mineRunning1.getId()))
                .singleElement()
                .satisfies(j -> {
                    assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING);
                    assertThat(j.getClaimedByInstance()).isNull();
                });
        assertThat(reloaded).filteredOn(j -> j.getId().equals(mineRunning2.getId()))
                .singleElement()
                .satisfies(j -> {
                    assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING);
                    assertThat(j.getClaimedByInstance()).isNull();
                });
        assertThat(reloaded).filteredOn(j -> j.getId().equals(otherRunning.getId()))
                .singleElement()
                .satisfies(j -> {
                    assertThat(j.getStatus())
                            .as("RUNNING rows owned by another instance must not be touched")
                            .isEqualTo(JobStatus.RUNNING);
                    assertThat(j.getClaimedByInstance()).isEqualTo("instance-B");
                });
        assertThat(reloaded).filteredOn(j -> j.getId().equals(pendingMine.getId()))
                .singleElement()
                .satisfies(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING));
        assertThat(reloaded).filteredOn(j -> j.getId().equals(succeededMine.getId()))
                .singleElement()
                .satisfies(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.SUCCEEDED));
        assertThat(reloaded).filteredOn(j -> j.getId().equals(failedMine.getId()))
                .singleElement()
                .satisfies(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.FAILED));
        assertThat(reloaded).filteredOn(j -> j.getId().equals(cancelledMine.getId()))
                .singleElement()
                .satisfies(j -> assertThat(j.getStatus()).isEqualTo(JobStatus.CANCELLED));
    }

    @Test
    void returnsZero_whenNothingToRequeue() {
        persistRunning(1L, "instance-B");
        persistTerminal(2L, JobStatus.SUCCEEDED, "instance-A");

        int requeued = jobRepository.requeueRunningOwnedBy("instance-A");

        assertThat(requeued).isZero();
    }

    private Job persistRunning(long userId, String instanceId) {
        Job job = baseJob(userId, JobStatus.RUNNING);
        job.setClaimedByInstance(instanceId);
        job.setClaimedAt(Instant.now());
        job.setStartedAt(Instant.now());
        return em.persistAndFlush(job);
    }

    private Job persistPending(long userId, String instanceId) {
        Job job = baseJob(userId, JobStatus.PENDING);
        job.setClaimedByInstance(instanceId);
        return em.persistAndFlush(job);
    }

    private Job persistTerminal(long userId, JobStatus status, String instanceId) {
        Job job = baseJob(userId, status);
        job.setClaimedByInstance(instanceId);
        job.setCompletedAt(Instant.now());
        return em.persistAndFlush(job);
    }

    private Job baseJob(long userId, JobStatus status) {
        Job job = new Job();
        job.setUserId(userId);
        job.setStatus(status);
        job.setPrompt("p");
        job.setCreditsCost(1);
        job.setProgress(0);
        return job;
    }
}
