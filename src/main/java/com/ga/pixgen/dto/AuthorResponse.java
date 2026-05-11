package com.ga.pixgen.dto;

import com.ga.pixgen.model.User;

import java.time.Instant;

public record AuthorResponse(
        Long id,
        String username,
        String profileImageUrl,
        Instant createdAt
) {
    public static AuthorResponse fromEntity(User user) {
        if (user == null) {
            return null;
        }
        return new AuthorResponse(
                user.getId(),
                user.getUsername(),
                user.getProfileImg(),
                user.getCreatedAt());
    }
}
