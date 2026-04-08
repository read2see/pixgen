package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ga.pixgen.model.JobStatus;

import java.time.Instant;

/**
 * Server-to-client envelope for job lifecycle and progress notifications.
 *
 * <p>The same record carries two flavours of event:
 * <ul>
 *     <li>{@code STATUS} — emitted on every state-machine transition
 *         ({@link JobStatus#PENDING}, {@link JobStatus#RUNNING},
 *         {@link JobStatus#SUCCEEDED}, {@link JobStatus#FAILED},
 *         {@link JobStatus#CANCELLED}). The {@code progress} field is null.</li>
 *     <li>{@code PROGRESS} — emitted by the worker between status changes.
 *         {@code progress} is a 0-100 integer; {@code status} is null.</li>
 * </ul>
 * Either way, {@code userId} drives broker fan-out and {@code jobId} lets
 * single-job streams filter the firehose.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobEventDto(
        Long jobId,
        Long userId,
        String type,
        JobStatus status,
        Integer progress,
        String message,
        Instant timestamp
) {

    public static final String TYPE_STATUS = "STATUS";
    public static final String TYPE_PROGRESS = "PROGRESS";

    public static JobEventDto status(Long jobId, Long userId, JobStatus status) {
        return status(jobId, userId, status, null);
    }

    public static JobEventDto status(Long jobId, Long userId, JobStatus status, String message) {
        return new JobEventDto(jobId, userId, TYPE_STATUS, status, null, message, Instant.now());
    }

    public static JobEventDto progress(Long jobId, Long userId, int progress) {
        return new JobEventDto(jobId, userId, TYPE_PROGRESS, null, progress, null, Instant.now());
    }
}
