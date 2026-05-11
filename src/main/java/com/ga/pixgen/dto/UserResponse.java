package com.ga.pixgen.dto;

import com.ga.pixgen.model.User;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String username,
        boolean verified,
        boolean enabled,
        Integer credits,
        String role,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public static UserResponse fromEntity(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.isVerified(),
                user.isEnabled(),
                user.getCredits(),
                user.getRole() != null ? user.getRole().getName() : null,
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getDeletedAt()
        );
    }
}
