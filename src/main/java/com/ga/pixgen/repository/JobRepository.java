package com.ga.pixgen.repository;

import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    @Query(value = """
            SELECT j.*
              FROM jobs j
             WHERE j.status = 'PENDING'
             ORDER BY (
                 SELECT COUNT(*)
                   FROM jobs r
                  WHERE r.user_id = j.user_id
                    AND r.status = 'RUNNING'
             ), j.created_at
             FOR UPDATE SKIP LOCKED
             LIMIT :slots
            """, nativeQuery = true)
    List<Job> claimNextPending(@Param("slots") int slots);

    long countByUserIdAndStatus(Long userId, JobStatus status);

    @Query("""
            SELECT COUNT(j)
              FROM Job j
             WHERE j.userId = :userId
               AND j.status IN (com.ga.pixgen.model.JobStatus.PENDING,
                                com.ga.pixgen.model.JobStatus.RUNNING)
            """)
    long countActiveByUser(@Param("userId") Long userId);

    List<Job> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Job> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, JobStatus status);

    /**
     * Atomically transition a still-{@code PENDING} job to {@code CANCELLED}.
     * Returns the number of rows updated; {@code 0} means the poller has
     * already claimed the job in between the lookup and this call.
     *
     * @param jobId the job id value
     * @return the int result
     */
    @Modifying
    @Query("""
            UPDATE Job j
               SET j.status = com.ga.pixgen.model.JobStatus.CANCELLED
             WHERE j.id = :id
               AND j.status = com.ga.pixgen.model.JobStatus.PENDING
            """)
    int markCancelledIfPending(@Param("id") Long jobId);

    /**
     * Atomically flag a {@code RUNNING} job for cancellation so the worker
     * — which may be on a different instance — can observe it on its next
     * progress tick. Returns the number of rows updated; {@code 0} means
     * the job is no longer {@code RUNNING}.
     *
     * @param jobId the job id value
     * @return the int result
     */
    @Modifying
    @Query("""
            UPDATE Job j
               SET j.cancelRequested = true
             WHERE j.id = :id
               AND j.status = com.ga.pixgen.model.JobStatus.RUNNING
            """)
    int markCancelRequestedIfRunning(@Param("id") Long jobId);

    /**
     * Update the {@code progress} column for a {@code RUNNING} job. The
     * status guard avoids racing with a terminal transition: once the
     * worker flips the row to {@code SUCCEEDED}/{@code FAILED}/{@code CANCELLED}
     * a stale progress callback must not roll the percentage back.
     *
     * @param progress the progress value
     * @return the int updateProgress(@Param("id") Long id, result
     */
    @Modifying
    @Query("""
            UPDATE Job j
               SET j.progress = :progress
             WHERE j.id = :id
               AND j.status = com.ga.pixgen.model.JobStatus.RUNNING
            """)
    int updateProgress(@Param("id") Long id, @Param("progress") int progress);

    /**
     * Atomically flip a {@code RUNNING} job to {@code SUCCEEDED}, force
     * progress to 100, and stamp the completion time. Native so the
     * timestamp comes from the database clock and so we don't need to
     * touch the {@code @Version} column on the cached entity.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'SUCCEEDED',
                   progress = 100,
                   completed_at = NOW(),
                   updated_at = NOW(),
                   version = version + 1
             WHERE id = :id
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int markSucceeded(@Param("id") Long id);

    /**
     * Atomically transition a job to {@code FAILED} from any non-terminal
     * state, persisting the supplied error message.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'FAILED',
                   error_message = :message,
                   completed_at = NOW(),
                   updated_at = NOW(),
                   version = version + 1
             WHERE id = :id
               AND status IN ('PENDING', 'RUNNING')
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("message") String message);

    /**
     * Atomically transition a job to {@code CANCELLED} from any
     * non-terminal state. Used by the worker when it observes
     * {@link Thread#isInterrupted()} or the {@code cancel_requested}
     * flag set by another instance.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'CANCELLED',
                   completed_at = NOW(),
                   updated_at = NOW(),
                   version = version + 1
             WHERE id = :id
               AND status IN ('PENDING', 'RUNNING')
            """, nativeQuery = true)
    int markCancelled(@Param("id") Long id);

    /**
     * Read just the {@code cancel_requested} flag without dragging the
     * full row through Hibernate's first-level cache. The worker polls
     * this between progress ticks so a cross-instance cancel is honoured
     * without an SSE round-trip.
     *
     * @param id the id value
     * @return the matching rows, which may be empty
     */
    @Query("""
            SELECT j.cancelRequested
              FROM Job j
             WHERE j.id = :id
            """)
    Optional<Boolean> findCancelRequested(@Param("id") Long id);

    /**
     * Count the number of {@code PENDING} jobs whose id is less than or equal
     * to {@code id}. Job ids are monotonic so the result is the 1-indexed
     * position of {@code id} in the global pending queue. Returns {@code 0}
     * when {@code id} is not (or no longer) pending.
     *
     * @param id the id value
     * @return the long result
     */
    @Query("""
            SELECT COUNT(j)
              FROM Job j
             WHERE j.status = com.ga.pixgen.model.JobStatus.PENDING
               AND j.id <= :id
            """)
    long countPendingNotAfter(@Param("id") Long id);

    /**
     * Reset every {@code RUNNING} row whose {@code claimed_by_instance}
     * matches the supplied identifier back to {@code PENDING} and clear
     * the instance tag. Called once at startup so a JVM that crashed
     * mid-flight can pick up its own abandoned work on the next boot
     * without stomping on rows owned by a different live instance.
     * Returns the number of rows reset.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'PENDING',
                   claimed_by_instance = NULL,
                   claimed_at = NULL,
                   started_at = NULL,
                   updated_at = NOW(),
                   version = version + 1
             WHERE status = 'RUNNING'
               AND claimed_by_instance = :instanceId
            """, nativeQuery = true)
    int requeueRunningOwnedBy(@Param("instanceId") String instanceId);
}
