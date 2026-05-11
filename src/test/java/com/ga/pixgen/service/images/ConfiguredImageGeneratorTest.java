package com.ga.pixgen.service.images;

import com.ga.pixgen.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfiguredImageGeneratorTest {

    @Mock
    private StubImageGenerator simulatedStrategy;

    @Mock
    private InternalServiceImageGenerationStrategy internalServiceStrategy;

    private AppProperties properties;
    private ConfiguredImageGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        generator = new ConfiguredImageGenerator(properties, simulatedStrategy, internalServiceStrategy);
    }

    @Test
    void generate_usesSimulatedStrategy_whenSimulationEnabled() throws Exception {
        properties.setInternalServiceSimulation(true);
        GenerationRequest request = sampleRequest();
        IntConsumer progressListener = ignoredProgress();
        StoredImage expected = new StoredImage("1/sim.png", 10L, 64, 64, "image/png");
        when(simulatedStrategy.generate(any(), any())).thenReturn(expected);

        StoredImage actual = generator.generate(request, progressListener);

        assertThat(actual).isSameAs(expected);
        verify(simulatedStrategy).generate(request, progressListener);
        verifyNoInteractions(internalServiceStrategy);
    }

    @Test
    void generate_usesInternalServiceStrategy_whenSimulationDisabled() throws Exception {
        properties.setInternalServiceSimulation(false);
        GenerationRequest request = sampleRequest();
        IntConsumer progressListener = ignoredProgress();
        StoredImage expected = new StoredImage("1/internal.png", 10L, 64, 64, "image/png");
        when(internalServiceStrategy.generate(any(), any())).thenReturn(expected);

        StoredImage actual = generator.generate(request, progressListener);

        assertThat(actual).isSameAs(expected);
        verify(internalServiceStrategy).generate(request, progressListener);
        verifyNoInteractions(simulatedStrategy);
    }

    private static GenerationRequest sampleRequest() {
        return new GenerationRequest(
                11L,
                1L,
                64,
                64,
                "prompt",
                null,
                null,
                null,
                null,
                null,
                "model.ckpt");
    }

    private static IntConsumer ignoredProgress() {
        return ignored -> {
        };
    }
}
