package com.ga.pixgen.dto;

import com.ga.pixgen.service.jobs.JobSubmission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * User-facing request body for {@code POST /api/jobs}.
 *
 * <p>Aligned with docker-diffusers-api {@code modelInputs} / {@code callInputs}:
 * {@code num_inference_steps}, {@code guidance_scale}, {@code MODEL_ID} as
 * {@code model_id}.</p>
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
        Integer numInferenceSteps,

        @Min(0)
        @Max(30)
        Double guidanceScale,

        Long seed,

        @Size(max = 64)
        String sampler,

        @NotBlank
        @Size(max = 256)
        String modelId
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
                numInferenceSteps,
                guidanceScale,
                seed,
                sampler,
                modelId);
    }
}
