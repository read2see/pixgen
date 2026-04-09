package com.ga.pixgen.repository;

import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
