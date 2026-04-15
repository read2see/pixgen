package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;

import java.time.Instant;

/**
 * Client-facing projection of a {@link Job} entity.
 *
 * <p>{@code queuePosition} is populated only while the job is still
 * {@link JobStatus#PENDING}; for any other status it is {@code null} and
 * suppressed from the JSON payload by {@link JsonInclude.Include#NON_NULL}.
 * {@code estimatedWaitMs} reserves a slot for the EWMA-based wait estimate
 * introduced by {@code JobMetrics} in a later branch — null today, plumbed
 * end-to-end so clients can opt into rendering it the moment it lights up.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(
        Long id,
        Long userId,
        JobStatus status,
        String prompt,
        String negativePrompt,
        Integer width,
        Integer height,
        Integer numInferenceSteps,
        Double guidanceScale,
        Long seed,
        String sampler,
        String modelId,
        Integer creditsCost,
        Integer progress,
        boolean cancelRequested,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        Integer queuePosition,
        Long estimatedWaitMs,
        Instant createdAt,
        Instant updatedAt
) {

    /** Build a response from {@code job}, with no queue/wait estimate populated. */
    public static JobResponse fromEntity(Job job) {
        return fromEntity(job, null, null);
    }

    /**
     * Build a response from {@code job} and the optional pending-queue
     * position computed by the caller. {@code queuePosition} is silently
     * dropped if {@code job.getStatus() != PENDING} so a stale value cannot
     * leak into a non-pending response.
     */
    public static JobResponse fromEntity(Job job, Integer queuePosition, Long estimatedWaitMs) {
        Integer position = job.getStatus() == JobStatus.PENDING ? queuePosition : null;
        return new JobResponse(
                job.getId(),
                job.getUserId(),
                job.getStatus(),
                job.getPrompt(),
                job.getNegativePrompt(),
                job.getWidth(),
                job.getHeight(),
                job.getNumInferenceSteps(),
                job.getGuidanceScale(),
                job.getSeed(),
                job.getSampler(),
                job.getModelId(),
                job.getCreditsCost(),
                job.getProgress(),
                job.isCancelRequested(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage(),
                position,
                position == null ? null : estimatedWaitMs,
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
