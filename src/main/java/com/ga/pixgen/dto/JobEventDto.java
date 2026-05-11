package com.ga.pixgen.dto;

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
 *         {@link JobStatus#CANCELLED}). Terminal success carries
 *         {@code progress=100}; failures and cancellations keep progress
 *         nullable because the last persisted tick may vary.</li>
 *     <li>{@code PROGRESS} — emitted by the worker between status changes.
 *         {@code progress} is a 0-100 integer and {@code status} remains
 *         {@link JobStatus#RUNNING} so EventSource clients can consume a
 *         complete frontend {@code JobEvent} contract from every tick.</li>
 * </ul>
 * Either way, {@code userId} drives broker fan-out and {@code jobId} lets
 * single-job streams filter the firehose. Null fields are intentionally
 * serialized so TypeScript clients receive the full shape they expect.</p>
 */
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
        return new JobEventDto(
                jobId,
                userId,
                TYPE_STATUS,
                status,
                progressForStatus(status),
                messageOrDefault(status, message),
                Instant.now());
    }

    public static JobEventDto snapshot(Long jobId,
                                       Long userId,
                                       JobStatus status,
                                       Integer progress,
                                       String message) {
        return new JobEventDto(
                jobId,
                userId,
                TYPE_STATUS,
                status,
                progressForSnapshot(status, progress),
                messageOrDefault(status, message),
                Instant.now());
    }

    public static JobEventDto progress(Long jobId, Long userId, int progress) {
        return new JobEventDto(
                jobId,
                userId,
                TYPE_PROGRESS,
                JobStatus.RUNNING,
                clampProgress(progress),
                "Rendering image",
                Instant.now());
    }

    private static Integer progressForStatus(JobStatus status) {
        if (status == JobStatus.PENDING || status == JobStatus.RUNNING) {
            return 0;
        }
        if (status == JobStatus.SUCCEEDED) {
            return 100;
        }
        return null;
    }

    private static Integer progressForSnapshot(JobStatus status, Integer progress) {
        if (status == JobStatus.SUCCEEDED) {
            return 100;
        }
        if (progress == null) {
            return progressForStatus(status);
        }
        return clampProgress(progress);
    }

    private static String messageOrDefault(JobStatus status, String message) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING -> "Job queued";
            case RUNNING -> "Rendering image";
            case SUCCEEDED -> "Generation complete";
            case FAILED -> "Generation failed";
            case CANCELLED -> "Generation cancelled";
        };
    }

    private static int clampProgress(int progress) {
        return Math.max(0, Math.min(100, progress));
    }
}
