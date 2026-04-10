package com.ga.pixgen.exception;

import com.ga.pixgen.model.JobStatus;

/**
 * Thrown when a cancel request targets a job that is no longer in a
 * cancellable state — either it has already finished
 * ({@link JobStatus#SUCCEEDED}, {@link JobStatus#FAILED},
 * {@link JobStatus#CANCELLED}) or the conditional update from
 * {@code PENDING} raced with the poller and affected zero rows. Maps to
 * HTTP 409 Conflict via {@code GlobalExceptionHandler}.
 */
public class JobNotCancellableException extends RuntimeException {

    public JobNotCancellableException(Long jobId, JobStatus status) {
        super("Job " + jobId + " cannot be cancelled in status " + status);
    }

    public JobNotCancellableException(Long jobId) {
        super("Job " + jobId + " cannot be cancelled");
    }
}
