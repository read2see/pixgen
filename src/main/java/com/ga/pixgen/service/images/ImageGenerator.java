package com.ga.pixgen.service.images;

import java.util.function.IntConsumer;

/**
 * Pluggable image-generation backend. Phase 2 ships a {@link StubImageGenerator}
 * placeholder; future phases swap in a real diffusion runtime behind the same
 * contract without touching the worker.
 *
 * <p>Implementations <strong>must</strong> propagate thread interruption as a
 * checked {@link InterruptedException} so the worker can translate cancellation
 * into the {@code CANCELLED} job state without inspecting backend-specific
 * exception types. They <strong>must</strong> also clean up any partially
 * written artifact when interrupted, so a cancelled job leaves the filesystem
 * exactly as it was found.</p>
 */
public interface ImageGenerator {

    /**
     * Run a single generation. {@code progressListener} receives values in the
     * inclusive range {@code [0, 100]}, monotonically non-decreasing, ending at
     * exactly {@code 100} on success.
     *
     * @param request the request value
     * @param progressListener the progress listener value
     * @return descriptor of the persisted artifact
     * @throws InterruptedException if the calling thread is interrupted before completion
     */
    StoredImage generate(GenerationRequest request, IntConsumer progressListener) throws InterruptedException;
}
