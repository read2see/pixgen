package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ga.pixgen.model.Image;

import java.time.Instant;

/**
 * Client-facing projection of an {@link Image} row plus its optional
 * generation metadata. The on-disk path is intentionally omitted — clients
 * fetch bytes via {@code GET /api/images/{id}/file}, not by guessing
 * filesystem locations from the API response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageResponse(
        Long id,
        Long userId,
        Long jobId,
        String prompt,
        String mimeType,
        Long fileSizeBytes,
        Integer width,
        Integer height,
        Instant createdAt,
        ImageMetadataDto metadata
) {

    public static ImageResponse fromEntity(Image image) {
        return fromEntity(image, null);
    }

    public static ImageResponse fromEntity(Image image, ImageMetadataDto metadata) {
        return new ImageResponse(
                image.getId(),
                image.getUserId(),
                image.getJob() != null ? image.getJob().getId() : null,
                image.getPrompt(),
                image.getMimeType(),
                image.getFileSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getCreatedAt(),
                metadata);
    }
}
