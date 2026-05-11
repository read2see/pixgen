package com.ga.pixgen.dto;

import jakarta.validation.constraints.Min;

public record IncreaseCreditsRequest(
        @Min(1)
        int amount
) {
}
