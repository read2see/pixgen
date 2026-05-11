package com.ga.pixgen.service.images;

import java.util.function.IntConsumer;

/**
 * Backend-specific image generation strategy used by the configured
 * {@link ImageGenerator} facade.
 */
public interface ImageGenerationStrategy {

    /**
     * Run a single generation request and persist the produced artifact.
     *
     * @param request the request value
     * @param progressListener progress callback in the inclusive range [0, 100]
     * @return descriptor of the persisted artifact
     * @throws InterruptedException if the calling worker is cancelled
     */
    StoredImage generate(GenerationRequest request, IntConsumer progressListener) throws InterruptedException;
}
