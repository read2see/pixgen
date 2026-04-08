package com.ga.pixgen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the local image sink. Bound from the {@code app.images} prefix
 * and consumed by {@code LocalImageStorage}. Kept separate from
 * {@link JobsProperties} so the two concerns can evolve independently —
 * storage is not necessarily local in production deployments.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.images")
public class ImagesProperties {

    /** Root directory under which generated PNGs are written, one sub-dir per user. */
    private String localDir = "./data/images";
}
