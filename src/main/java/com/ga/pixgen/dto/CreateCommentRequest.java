package com.ga.pixgen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        Long parentId,

        @NotBlank
        @Size(max = 4000)
        String body
) {
}
