package com.ga.pixgen.dto;

import com.ga.pixgen.model.JobStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JobEventDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void progressEvent_matchesFrontendContract() {
        JobEventDto event = JobEventDto.progress(123L, 7L, 45);

        assertThat(event.jobId()).isEqualTo(123L);
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.type()).isEqualTo(JobEventDto.TYPE_PROGRESS);
        assertThat(event.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(event.progress()).isEqualTo(45);
        assertThat(event.message()).isEqualTo("Rendering image");
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void statusEvent_setsFrontendProgressAndDefaultMessageForSuccess() {
        JobEventDto event = JobEventDto.status(123L, 7L, JobStatus.SUCCEEDED);

        assertThat(event.type()).isEqualTo(JobEventDto.TYPE_STATUS);
        assertThat(event.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(event.progress()).isEqualTo(100);
        assertThat(event.message()).isEqualTo("Generation complete");
    }

    @Test
    void statusEvent_serializesNullFieldsInsteadOfOmittingThem() throws Exception {
        JobEventDto event = new JobEventDto(
                123L,
                7L,
                JobEventDto.TYPE_STATUS,
                JobStatus.FAILED,
                null,
                "model exploded",
                null);

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"jobId\":123");
        assertThat(json).contains("\"userId\":7");
        assertThat(json).contains("\"type\":\"STATUS\"");
        assertThat(json).contains("\"status\":\"FAILED\"");
        assertThat(json).contains("\"progress\":null");
        assertThat(json).contains("\"message\":\"model exploded\"");
        assertThat(json).contains("\"timestamp\":null");
    }

    @Test
    void progressEvent_clampsProgressToFrontendRange() {
        assertThat(JobEventDto.progress(123L, 7L, -10).progress()).isZero();
        assertThat(JobEventDto.progress(123L, 7L, 140).progress()).isEqualTo(100);
    }
}
