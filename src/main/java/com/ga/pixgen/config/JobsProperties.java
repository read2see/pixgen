package com.ga.pixgen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Tunables for the job pipeline. Bound from the {@code app.jobs} prefix and
 * shared by {@code JobExecutorConfig}, the scheduler/poller, the worker pool,
 * the active-job registry and the per-user concurrency caps.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.jobs")
public class JobsProperties {

    /** Maximum concurrently running jobs on this JVM (worker pool size). */
    private int maxJobsPerInstance = 4;

    /** Maximum jobs a single user may have in {@code RUNNING} state cluster-wide. */
    private int maxActiveJobsPerUser = 2;

    /** Maximum {@code PENDING} jobs a single user may have queued cluster-wide. */
    private int maxPendingJobsPerUser = 10;

    /** How frequently the scheduler polls the database for claimable work. */
    private long pollIntervalMs = 1000L;

    /** Credits charged per produced image on {@code SUCCEEDED}. */
    private int creditsPerImage = 1;

    /**
     * Stable identifier for this instance, used when claiming rows so that other
     * instances can recognise abandoned work on startup recovery. Defaults to a
     * random UUID so each JVM gets a unique value out of the box.
     */
    private String instanceId = UUID.randomUUID().toString();

    /** Lower bound of the stub generator's sleep, in milliseconds. */
    private long stubMinMs = 2000L;

    /** Upper bound of the stub generator's sleep, in milliseconds. */
    private long stubMaxMs = 5000L;
}
