package com.ga.pixgen.service.profile;

import com.ga.pixgen.config.ProfileImagesProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Filesystem-backed store for user profile images. Stub until TDD drives the full impl.
 */
@Component
public class ProfileImageStorage {

    @SuppressWarnings("unused")
    private final Path root;

    public ProfileImageStorage(ProfileImagesProperties properties) {
        this.root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
    }

    /**
     * @param userId      owner id (directory scope)
     * @param bytes       raw file bytes (non-null)
     * @param contentType MIME type e.g. image/jpeg — must be an allowed profile type
     */
    public StoredProfileImage write(Long userId, byte[] bytes, String contentType) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Path resolve(String relativePath) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void delete(String relativePath) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
