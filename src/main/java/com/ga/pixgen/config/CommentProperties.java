package com.ga.pixgen.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pixgen.comments")
public record CommentProperties(
        @Min(1)
        int maxDepth
) {
}
