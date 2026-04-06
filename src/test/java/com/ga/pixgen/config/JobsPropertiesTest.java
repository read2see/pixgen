package com.ga.pixgen.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JobsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JobsPropertiesTestConfig.class);

    @Test
    void bindsAllPropertiesFromAppJobsPrefix() {
        contextRunner
                .withPropertyValues(
                        "app.jobs.max-jobs-per-instance=8",
                        "app.jobs.max-active-jobs-per-user=3",
                        "app.jobs.max-pending-jobs-per-user=20",
                        "app.jobs.poll-interval-ms=500",
                        "app.jobs.credits-per-image=5",
                        "app.jobs.instance-id=instance-abc",
                        "app.jobs.stub-min-ms=1000",
                        "app.jobs.stub-max-ms=4000"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(JobsProperties.class);
                    JobsProperties props = context.getBean(JobsProperties.class);
                    assertThat(props.getMaxJobsPerInstance()).isEqualTo(8);
                    assertThat(props.getMaxActiveJobsPerUser()).isEqualTo(3);
                    assertThat(props.getMaxPendingJobsPerUser()).isEqualTo(20);
                    assertThat(props.getPollIntervalMs()).isEqualTo(500L);
                    assertThat(props.getCreditsPerImage()).isEqualTo(5);
                    assertThat(props.getInstanceId()).isEqualTo("instance-abc");
                    assertThat(props.getStubMinMs()).isEqualTo(1000L);
                    assertThat(props.getStubMaxMs()).isEqualTo(4000L);
                });
    }

    @Test
    void hasSensibleDefaultsWhenNoPropertiesProvided() {
        contextRunner.run(context -> {
            JobsProperties props = context.getBean(JobsProperties.class);
            assertThat(props.getMaxJobsPerInstance())
                    .as("default worker count must be > 0")
                    .isPositive();
            assertThat(props.getMaxActiveJobsPerUser()).isPositive();
            assertThat(props.getMaxPendingJobsPerUser()).isPositive();
            assertThat(props.getPollIntervalMs()).isPositive();
            assertThat(props.getCreditsPerImage()).isPositive();
            assertThat(props.getStubMinMs()).isPositive();
            assertThat(props.getStubMaxMs()).isGreaterThanOrEqualTo(props.getStubMinMs());
            assertThat(props.getInstanceId())
                    .as("instance-id must default to a non-blank identifier")
                    .isNotBlank();
        });
    }

    @Test
    void defaultInstanceIdIsUnique() {
        // Each fresh load of defaults must produce its own identifier; otherwise
        // two JVMs would race for the same claimed_by_instance value.
        contextRunner.run(first ->
                contextRunner.run(second -> {
                    String firstId = first.getBean(JobsProperties.class).getInstanceId();
                    String secondId = second.getBean(JobsProperties.class).getInstanceId();
                    assertThat(firstId).isNotBlank();
                    assertThat(secondId).isNotBlank();
                    assertThat(firstId).isNotEqualTo(secondId);
                }));
    }

    @EnableConfigurationProperties(JobsProperties.class)
    static class JobsPropertiesTestConfig {
    }
}
