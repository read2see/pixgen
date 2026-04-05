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

@Transactional
class JobRepositoryClaimTest extends AbstractPostgresContainerTest {

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
    void claimsOnlyPendingJobsUpToLimit() {
        long u = 1L;
        Job p1 = persistJob(u, JobStatus.PENDING, Instant.now().minusSeconds(30));
        Job p2 = persistJob(u, JobStatus.PENDING, Instant.now().minusSeconds(20));
        persistJob(u, JobStatus.RUNNING, Instant.now().minusSeconds(40));
        persistJob(u, JobStatus.SUCCEEDED, Instant.now().minusSeconds(50));

        List<Job> claimed = jobRepository.claimNextPending(2);

        assertThat(claimed).extracting(Job::getId)
                .containsExactly(p1.getId(), p2.getId());
        assertThat(claimed).allSatisfy(j ->
                assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING));
    }

    @Test
    void claimOrdersByPerUserRunningCountThenCreatedAt() {
        long u1 = 10L;
        long u2 = 20L;

        persistJob(u1, JobStatus.RUNNING, Instant.now().minusSeconds(100));
        persistJob(u1, JobStatus.RUNNING, Instant.now().minusSeconds(90));

        Job u1Pending = persistJob(u1, JobStatus.PENDING, Instant.now().minusSeconds(80));
        Job u2PendingOlder = persistJob(u2, JobStatus.PENDING, Instant.now().minusSeconds(60));
        Job u2PendingNewer = persistJob(u2, JobStatus.PENDING, Instant.now().minusSeconds(20));

        List<Job> claimed = jobRepository.claimNextPending(3);

        assertThat(claimed).extracting(Job::getId)
                .containsExactly(u2PendingOlder.getId(), u2PendingNewer.getId(), u1Pending.getId());
    }

    @Test
    void claimReturnsEmptyWhenNoPendingJobs() {
        persistJob(1L, JobStatus.RUNNING, Instant.now());
        persistJob(1L, JobStatus.SUCCEEDED, Instant.now());

        List<Job> claimed = jobRepository.claimNextPending(5);

        assertThat(claimed).isEmpty();
    }

    @Test
    void countsRunningByUser() {
        long u = 7L;
        persistJob(u, JobStatus.RUNNING, Instant.now());
        persistJob(u, JobStatus.RUNNING, Instant.now());
        persistJob(u, JobStatus.PENDING, Instant.now());
        persistJob(u, JobStatus.SUCCEEDED, Instant.now());
        persistJob(8L, JobStatus.RUNNING, Instant.now());

        long running = jobRepository.countByUserIdAndStatus(u, JobStatus.RUNNING);
        long succeeded = jobRepository.countByUserIdAndStatus(u, JobStatus.SUCCEEDED);

        assertThat(running).isEqualTo(2L);
        assertThat(succeeded).isEqualTo(1L);
    }

    @Test
    void countsPendingByUser() {
        long u = 9L;
        persistJob(u, JobStatus.PENDING, Instant.now());
        persistJob(u, JobStatus.PENDING, Instant.now());
        persistJob(u, JobStatus.PENDING, Instant.now());
        persistJob(u, JobStatus.RUNNING, Instant.now());
        persistJob(11L, JobStatus.PENDING, Instant.now());

        long pending = jobRepository.countByUserIdAndStatus(u, JobStatus.PENDING);

        assertThat(pending).isEqualTo(3L);
    }

    @Test
    void countsActiveByUser_includesPendingAndRunningOnly() {
        long u = 12L;
        persistJob(u, JobStatus.PENDING, Instant.now());
        persistJob(u, JobStatus.RUNNING, Instant.now());
        persistJob(u, JobStatus.SUCCEEDED, Instant.now());
        persistJob(u, JobStatus.FAILED, Instant.now());
        persistJob(u, JobStatus.CANCELLED, Instant.now());

        long active = jobRepository.countActiveByUser(u);

        assertThat(active).isEqualTo(2L);
    }

    private Job persistJob(long userId, JobStatus status, Instant createdAt) {
        Job job = new Job();
        job.setUserId(userId);
        job.setStatus(status);
        job.setPrompt("p");
        job.setCreditsCost(1);
        job.setProgress(0);
        Job saved = em.persistAndFlush(job);
        em.getEntityManager()
                .createNativeQuery("UPDATE jobs SET created_at = :ts WHERE id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        em.refresh(saved);
        return saved;
    }
}
