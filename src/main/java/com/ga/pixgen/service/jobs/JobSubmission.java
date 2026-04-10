package com.ga.pixgen.service.jobs;

/**
 * Service-layer command bag for {@link JobService#submit(com.ga.pixgen.model.User, JobSubmission)}.
 * Mirrors the user-facing {@code CreateJobRequest} DTO without coupling
 * the service to the HTTP layer; the controller branch will introduce
 * the request DTO and translate it into this record.
 *
 * <p>All fields are nullable except {@code prompt}; missing values are
 * left null on the {@link com.ga.pixgen.model.Job Job} entity so the
 * generation backend (or its defaults) can decide what to do with them.</p>
 */
public record JobSubmission(
        String prompt,
        String negativePrompt,
        Integer width,
        Integer height,
        Integer steps,
        Double cfgScale,
        Long seed,
        String sampler,
        String modelName
) {
}
