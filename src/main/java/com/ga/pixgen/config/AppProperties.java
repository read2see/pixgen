package com.ga.pixgen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level application toggles that do not belong to a narrower subsystem.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * When true, image generation uses the in-process simulated backend. When
     * false, generation is delegated to the configured internal service.
     */
    private boolean internalServiceSimulation = true;
}
