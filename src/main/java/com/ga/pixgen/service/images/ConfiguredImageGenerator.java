package com.ga.pixgen.service.images;

import com.ga.pixgen.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;

/**
 * Single worker-facing generator that routes to the configured backend
 * strategy. Adding another provider later should only require another strategy
 * and a routing option here, not changes to the job pipeline.
 */
@Component
public class ConfiguredImageGenerator implements ImageGenerator {

    private final AppProperties properties;
    private final StubImageGenerator simulatedStrategy;
    private final InternalServiceImageGenerationStrategy internalServiceStrategy;

    public ConfiguredImageGenerator(AppProperties properties,
                                    StubImageGenerator simulatedStrategy,
                                    InternalServiceImageGenerationStrategy internalServiceStrategy) {
        this.properties = properties;
        this.simulatedStrategy = simulatedStrategy;
        this.internalServiceStrategy = internalServiceStrategy;
    }

    @Override
    public StoredImage generate(GenerationRequest request, IntConsumer progressListener)
            throws InterruptedException {
        ImageGenerationStrategy strategy = properties.isInternalServiceSimulation()
                ? simulatedStrategy
                : internalServiceStrategy;
        return strategy.generate(request, progressListener);
    }
}
