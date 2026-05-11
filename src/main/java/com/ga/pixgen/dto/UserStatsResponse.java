package com.ga.pixgen.dto;

public record UserStatsResponse(
        long activeJobs,
        long generatedImages,
        Integer credits
) {
}
