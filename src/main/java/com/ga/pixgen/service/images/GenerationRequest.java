package com.ga.pixgen.service.images;

/**
 * Immutable bag of parameters handed to {@link ImageGenerator#generate}. Modeled
 * as a record so the worker can build it once, log it, and pass it through.
 *
 * @param jobId  database id of the owning job (for storage path scoping / log correlation)
 * @param userId database id of the owning user (used as the storage sub-directory)
 * @param width  requested output width in pixels (> 0)
 * @param height requested output height in pixels (> 0)
 * @param prompt user-supplied text prompt; ignored by the stub but logged in production
 * @param seed   deterministic seed; used by the stub to drive its placeholder gradient
 */
public record GenerationRequest(
        Long jobId,
        Long userId,
        int width,
        int height,
        String prompt,
        Long seed
) {
}
