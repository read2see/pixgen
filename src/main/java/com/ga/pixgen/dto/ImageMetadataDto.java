package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ga.pixgen.model.ImageMetadata;

/**
 * Client-facing projection of {@link ImageMetadata}. Only generation
 * parameters that may interest the user (model, sampler, seed, …) are
 * surfaced; storage-internal fields are intentionally omitted. Fields
 * absent on the entity are dropped from the payload via
 * {@link JsonInclude.Include#NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageMetadataDto(
        String modelName,
        String sampler,
        Integer steps,
        Double cfgScale,
        Long seed,
        String scheduler,
        Integer clipSkip,
        String lorasJson,
        String extrasJson
) {

    public static ImageMetadataDto fromEntity(ImageMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return new ImageMetadataDto(
                metadata.getModelName(),
                metadata.getSampler(),
                metadata.getSteps(),
                metadata.getCfgScale(),
                metadata.getSeed(),
                metadata.getScheduler(),
                metadata.getClipSkip(),
                metadata.getLorasJson(),
                metadata.getExtrasJson());
    }
}
