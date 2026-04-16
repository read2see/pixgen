package com.ga.pixgen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for local profile-avatar storage. Bound from {@code app.profile-images}
 * and kept separate from {@link ImagesProperties} so generated job assets and
 * user-uploaded avatars never share a root by accident.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.profile-images")
public class ProfileImagesProperties {

    /** Root directory under which profile images are written, one sub-dir per user. */
    private String localDir = "./data/profile-images";
}
