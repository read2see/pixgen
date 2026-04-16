package com.ga.pixgen.service.profile;

/**
 * Result of persisting profile image bytes through {@link ProfileImageStorage}.
 *
 * @param relativePath forward-slash path under the configured root (e.g. {@code 7/uuid.jpg})
 * @param sizeBytes    file size on disk
 * @param mimeType     normalized content type (e.g. {@code image/jpeg})
 */
public record StoredProfileImage(String relativePath, long sizeBytes, String mimeType) {
}
