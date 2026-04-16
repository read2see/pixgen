package com.ga.pixgen.service.profile;

import com.ga.pixgen.config.ProfileImagesProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

/**
 * Filesystem-backed store for user profile images. Bytes land at
 * {@code {localDir}/{userId}/{uuid}.{ext}}; only {@code image/jpeg},
 * {@code image/png}, and {@code image/webp} are accepted.
 */
@Component
public class ProfileImageStorage {

    private final Path root;

    public ProfileImageStorage(ProfileImagesProperties properties) {
        this.root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create profile image storage root: " + this.root, e);
        }
    }

    /**
     * @param userId      owner id (directory scope)
     * @param bytes       raw file bytes (non-null)
     * @param contentType MIME type e.g. {@code image/jpeg} — must be an allowed profile type
     */
    public StoredProfileImage write(Long userId, byte[] bytes, String contentType) {
        String mime = normalizeMime(contentType);
        String ext = extensionForMime(mime);
        Path userDir = root.resolve(userId.toString());
        try {
            Files.createDirectories(userDir);
            String fileName = UUID.randomUUID() + ext;
            Path target = userDir.resolve(fileName);
            Files.write(target, bytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            String relative = userId + "/" + fileName;
            return new StoredProfileImage(relative, bytes.length, mime);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to persist profile image for user " + userId + " under " + userDir, e);
        }
    }

    /**
     * Resolve a relative path under the storage root. Rejects values that escape the root.
     */
    public Path resolve(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Refusing to resolve path that escapes the storage root: " + relativePath);
        }
        return resolved;
    }

    /**
     * Delete a previously written file. Idempotent — a missing file is a no-op.
     */
    public void delete(String relativePath) {
        Path resolved = resolve(relativePath);
        try {
            Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete profile image at " + resolved, e);
        }
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Content type is required");
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(base)) {
            return "image/jpeg";
        }
        return switch (base) {
            case "image/jpeg", "image/png", "image/webp" -> base;
            default -> throw new IllegalArgumentException("Unsupported profile image type: " + contentType);
        };
    }

    private static String extensionForMime(String mime) {
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Unsupported profile image type: " + mime);
        };
    }
}
