package com.ga.pixgen.service.images;

/**
 * Immutable bag of parameters handed to {@link ImageGenerator#generate}. Mirrors
 * docker-diffusers-api {@code modelInputs} / {@code callInputs} fields the
 * backend will forward to a diffusion runtime.
 *
 * @param jobId              database id of the owning job (for storage path scoping / log correlation)
 * @param userId             database id of the owning user (used as the storage sub-directory)
 * @param width              requested output width in pixels (positive)
 * @param height             requested output height in pixels (positive)
 * @param prompt             user-supplied text prompt
 * @param negativePrompt     optional negative prompt
 * @param numInferenceSteps  optional; docker-diffusers {@code num_inference_steps}
 * @param guidanceScale      optional; docker-diffusers {@code guidance_scale}
 * @param seed               optional deterministic seed (converted to a generator downstream)
 * @param sampler            optional sampler name
 * @param modelId            docker-diffusers {@code MODEL_ID}
 */
public record GenerationRequest(
        Long jobId,
        Long userId,
        int width,
        int height,
        String prompt,
        String negativePrompt,
        Integer numInferenceSteps,
        Double guidanceScale,
        Long seed,
        String sampler,
        String modelId
) {
}
