package com.ga.pixgen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * HTTP settings for the internal image generation service.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.internal-service")
public class InternalServiceProperties {

    /** Base URL of the internal service, without a required trailing slash. */
    private String baseUrl = "http://localhost:8000";

    /** Connection timeout for each internal service request. */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** Per-request timeout for submit, poll, cancel and image download calls. */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Delay between job status polls while waiting for generation to finish. */
    private Duration pollInterval = Duration.ofSeconds(1);
}
