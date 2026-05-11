package com.ga.pixgen.dto;

import jakarta.validation.constraints.Size;

public record ModerationReasonRequest(
        @Size(max = 1000)
        String reason
) {
}
