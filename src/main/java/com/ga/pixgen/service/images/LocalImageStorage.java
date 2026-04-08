package com.ga.pixgen.service.images;

import com.ga.pixgen.config.ImagesProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Filesystem-backed image sink. Each write lands at
 * {@code {localDir}/{userId}/{uuid}.png}; the user directory is created
 * lazily so we don't need a startup migration step.
 *
 * <p>The class deliberately exposes a tiny surface: {@link #write}, {@link #resolve}
 * and {@link #delete}. Callers persist only the relative path, which keeps
 * database rows decoupled from the absolute mount point — useful when the
 * volume moves or when a future production storage implementation swaps in
 * behind the same contract.</p>
 *
 * <p>{@link #resolve} guards against path-traversal attempts so a hostile or
 * corrupted {@code file_path} value cannot read files outside the configured
 * root via the file-serving endpoint.</p>
 */
@Component
public class LocalImageStorage {

    private static final String PNG_MIME = "image/png";
    private static final String PNG_EXT = ".png";

    private final Path root;

    public LocalImageStorage(ImagesProperties properties) {
        this.root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create image storage root: " + this.root, e);
        }
    }

    /**
     * Persist {@code pngBytes} under the given user's directory and return a
     * {@link StoredImage} describing the result. The returned relative path is
     * forward-slash separated regardless of OS so it is safe to put into a
     * database column and serve back as a URL fragment later.
     *
     * @param userId the user id value
     * @param pngBytes the png bytes value
     * @param width the width value
     * @param height the height value
     * @return the StoredImage result
     */
    public StoredImage write(Long userId, byte[] pngBytes, int width, int height) {
        Path userDir = root.resolve(userId.toString());
        try {
            Files.createDirectories(userDir);
            String fileName = UUID.randomUUID() + PNG_EXT;
            Path target = userDir.resolve(fileName);
            Files.write(target, pngBytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            String relative = userId + "/" + fileName;
            return new StoredImage(relative, pngBytes.length, width, height, PNG_MIME);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to persist image for user " + userId + " under " + userDir, e);
        }
    }

    /**
     * Resolve a relative path produced by {@link #write} to an absolute filesystem
     * path. Throws {@link IllegalArgumentException} if the path would escape the
     * configured root, so callers can hand untrusted database values straight in.
     *
     * @param relativePath the relative path value
     * @return the Path result
     */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Refusing to resolve path that escapes the storage root: " + relativePath);
        }
        return resolved;
    }

    /**
     * Delete a previously written file. Idempotent — a missing file is a no-op so
     * cleanup paths in the worker can run safely after a partial failure.
     *
     * @param relativePath the relative path value
     */
    public void delete(String relativePath) {
        Path resolved = resolve(relativePath);
        try {
            Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete image at " + resolved, e);
        }
    }
}
