package com.ga.pixgen.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServicePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesTestConfig.class);

    @Test
    void bindsSimulationToggleAndInternalServiceSettings() {
        contextRunner
                .withPropertyValues(
                        "app.internal-service-simulation=false",
                        "app.internal-service.base-url=http://internal-pixgen:8000",
                        "app.internal-service.connect-timeout=2s",
                        "app.internal-service.request-timeout=45s",
                        "app.internal-service.poll-interval=250ms"
                )
                .run(context -> {
                    AppProperties app = context.getBean(AppProperties.class);
                    InternalServiceProperties internal = context.getBean(InternalServiceProperties.class);

                    assertThat(app.isInternalServiceSimulation()).isFalse();
                    assertThat(internal.getBaseUrl()).isEqualTo("http://internal-pixgen:8000");
                    assertThat(internal.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(internal.getRequestTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(internal.getPollInterval()).isEqualTo(Duration.ofMillis(250));
                });
    }

    @Test
    void defaultsKeepSimulationEnabled() {
        contextRunner.run(context -> {
            AppProperties app = context.getBean(AppProperties.class);
            InternalServiceProperties internal = context.getBean(InternalServiceProperties.class);

            assertThat(app.isInternalServiceSimulation()).isTrue();
            assertThat(internal.getBaseUrl()).isEqualTo("http://localhost:8000");
            assertThat(internal.getConnectTimeout()).isPositive();
            assertThat(internal.getRequestTimeout()).isPositive();
            assertThat(internal.getPollInterval()).isPositive();
        });
    }

    @EnableConfigurationProperties({AppProperties.class, InternalServiceProperties.class})
    static class PropertiesTestConfig {
    }
}
