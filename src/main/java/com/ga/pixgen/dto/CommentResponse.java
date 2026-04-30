package com.ga.pixgen.dto;

import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;

import java.time.Instant;

public record CommentResponse(
        Long id,
        Long postId,
        Long userId,
        Long parentId,
        String path,
        Integer depth,
        String body,
        CommentStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static CommentResponse fromEntity(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getUserId(),
                comment.getParent() != null ? comment.getParent().getId() : null,
                comment.getPath(),
                comment.getDepth(),
                comment.getBody(),
                comment.getStatus(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
