package com.ga.pixgen.service.jobs;

import com.ga.pixgen.model.Job;
import org.springframework.stereotype.Component;

/**
 * Single-job execution unit submitted to {@code jobWorkerExecutor} by
 * {@link JobScheduler}. The body of {@link #execute(Job)} runs the
 * generator, fans progress out through {@link JobEventBroker}, persists
 * the produced image, deducts credits, and finally releases the
 * {@link ActiveJobRegistry} slot so the next poll can claim more work.
 *
 * <p>Implementation lands in the next commit; this stub exists so the
 * scheduler can wire its dependency without forcing the worker's tests to
 * land in the same red commit.</p>
 */
@Component
public class JobWorker {

    public void execute(Job job) {
        // Placeholder. The real lifecycle is implemented in the next commit
        // alongside its own dedicated test suite.
        throw new UnsupportedOperationException("JobWorker.execute is not implemented yet");
    }
}
