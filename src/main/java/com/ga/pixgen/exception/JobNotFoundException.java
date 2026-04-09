package com.ga.pixgen.exception;

/**
 * Thrown when a request targets a job id that does not exist. Distinct
 * from the generic {@link ResourceNotFoundException} so the exception
 * handler can rely on the type when shaping job-specific responses, but
 * still maps to HTTP 404 Not Found.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(Long jobId) {
        super("Job with id " + jobId + " not found");
    }
}
