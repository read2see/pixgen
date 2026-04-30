package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ga.pixgen.model.Post;
import com.ga.pixgen.model.PostImage;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostResponse(
        Long id,
        Long userId,
        String title,
        String body,
        PostStatus status,
        PostVisibility visibility,
        List<ImageResponse> images,
        Instant createdAt,
        Instant updatedAt
) {

    public static PostResponse fromEntity(Post post, List<PostImage> images) {
        return new PostResponse(
                post.getId(),
                post.getUserId(),
                post.getTitle(),
                post.getBody(),
                post.getStatus(),
                post.getVisibility(),
                images.stream()
                        .sorted(Comparator.comparing(PostImage::getSortOrder))
                        .map(PostImage::getImage)
                        .map(ImageResponse::fromEntity)
                        .toList(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }
}
