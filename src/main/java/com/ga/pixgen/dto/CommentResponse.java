package com.ga.pixgen.dto;

import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;

import java.time.Instant;

public record CommentResponse(
        Long id,
        Long postId,
        Long userId,
        AuthorResponse author,
        String authorUsername,
        String username,
        Long parentId,
        String path,
        Integer depth,
        String body,
        CommentStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public CommentResponse(Long id,
                           Long postId,
                           Long userId,
                           Long parentId,
                           String path,
                           Integer depth,
                           String body,
                           CommentStatus status,
                           Instant createdAt,
                           Instant updatedAt) {
        this(id, postId, userId, null, null, null, parentId, path, depth, body, status, createdAt, updatedAt);
    }

    public static CommentResponse fromEntity(Comment comment) {
        return fromEntity(comment, null);
    }

    public static CommentResponse fromEntity(Comment comment, AuthorResponse author) {
        return new CommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getUserId(),
                author,
                author != null ? author.username() : null,
                author != null ? author.username() : null,
                comment.getParent() != null ? comment.getParent().getId() : null,
                comment.getPath(),
                comment.getDepth(),
                comment.getBody(),
                comment.getStatus(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
