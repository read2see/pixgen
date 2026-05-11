package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ga.pixgen.model.Image;

import java.time.Instant;

/**
 * Client-facing projection of an {@link Image} row including generation parameters
 * (formerly on {@code ImageMetadata}). The on-disk path is intentionally omitted —
 * clients fetch bytes via {@code GET /api/images/{id}/file}, not by guessing
 * filesystem locations from the API response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageResponse(
        Long id,
        Long userId,
        Long jobId,
        String prompt,
        String negativePrompt,
        String mimeType,
        Long fileSizeBytes,
        Integer width,
        Integer height,
        String modelId,
        String sampler,
        Integer numInferenceSteps,
        Double guidanceScale,
        Long seed,
        String scheduler,
        Integer clipSkip,
        String lorasJson,
        String extrasJson,
        Instant createdAt,
        Instant updatedAt,
        String fileUrlTemplate
) {

    public ImageResponse(Long id,
                         Long userId,
                         Long jobId,
                         String prompt,
                         String negativePrompt,
                         String mimeType,
                         Long fileSizeBytes,
                         Integer width,
                         Integer height,
                         String modelId,
                         String sampler,
                         Integer numInferenceSteps,
                         Double guidanceScale,
                         Long seed,
                         String scheduler,
                         Integer clipSkip,
                         String lorasJson,
                         String extrasJson,
                         Instant createdAt) {
        this(id, userId, jobId, prompt, negativePrompt, mimeType, fileSizeBytes, width, height, modelId,
                sampler, numInferenceSteps, guidanceScale, seed, scheduler, clipSkip, lorasJson, extrasJson,
                createdAt, null, null);
    }

    public static ImageResponse fromEntity(Image image) {
        return new ImageResponse(
                image.getId(),
                image.getUserId(),
                image.getJob() != null ? image.getJob().getId() : null,
                image.getPrompt(),
                image.getNegativePrompt(),
                image.getMimeType(),
                image.getFileSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getModelId(),
                image.getSampler(),
                image.getNumInferenceSteps(),
                image.getGuidanceScale(),
                image.getSeed(),
                image.getScheduler(),
                image.getClipSkip(),
                image.getLorasJson(),
                image.getExtrasJson(),
                image.getCreatedAt(),
                image.getUpdatedAt(),
                "/api/images/{id}/file");
    }
}
