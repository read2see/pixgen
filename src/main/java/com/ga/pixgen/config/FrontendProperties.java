package com.ga.pixgen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Front-end URLs used for redirects and browser CORS checks.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.frontend")
public class FrontendProperties {

    /** Browser-facing front-end origin. A missing scheme is treated as http. */
    private String baseUrl = "http://localhost:3000";

    public String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.frontend.base-url must not be blank");
        }
        String trimmed = baseUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
