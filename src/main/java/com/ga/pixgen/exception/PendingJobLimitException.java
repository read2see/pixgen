package com.ga.pixgen.exception;

/**
 * Thrown when a user tries to submit a job while already at
 * {@code app.jobs.max-pending-jobs-per-user} queued jobs. Maps to HTTP
 * 429 Too Many Requests via {@code GlobalExceptionHandler}.
 */
public class PendingJobLimitException extends RuntimeException {

    public PendingJobLimitException(int limit) {
        super("Pending job limit reached (max " + limit + ")");
    }
}
