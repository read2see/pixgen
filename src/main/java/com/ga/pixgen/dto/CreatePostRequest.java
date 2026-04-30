package com.ga.pixgen.dto;

import com.ga.pixgen.model.PostVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePostRequest(
        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 4000)
        String body,

        PostVisibility visibility,

        @NotEmpty
        @Size(max = 20)
        List<Long> imageIds
) {
}
