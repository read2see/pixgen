package com.ga.pixgen.dto;

import com.ga.pixgen.service.jobs.JobSubmission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * User-facing request body for {@code POST /api/jobs}.
 *
 * <p>The fields mirror {@link JobSubmission} but carry validation
 * constraints so client mistakes surface as {@code 400 Bad Request}
 * instead of bubbling into the service layer. Optional knobs are left
 * unannotated (other than reasonable upper bounds) so users can omit
 * them and let the generator backend pick its defaults.</p>
 */
public record CreateJobRequest(
        @NotBlank
        @Size(max = 4000)
        String prompt,

        @Size(max = 4000)
        String negativePrompt,

        @Min(64)
        @Max(2048)
        Integer width,

        @Min(64)
        @Max(2048)
        Integer height,

        @Min(1)
        @Max(150)
        Integer steps,

        @Min(0)
        @Max(30)
        Double cfgScale,

        Long seed,

        @Size(max = 64)
        String sampler,

        @Size(max = 128)
        String modelName
) {

    /**
     * Translate this request into the service-layer command. Kept here so the
     * controller stays a one-liner and the mapping is a unit-testable detail
     * of the DTO rather than the controller surface.
     */
    public JobSubmission toSubmission() {
        return new JobSubmission(
                prompt,
                negativePrompt,
                width,
                height,
                steps,
                cfgScale,
                seed,
                sampler,
                modelName);
    }
}
