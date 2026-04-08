package com.ga.pixgen.service.images;

/**
 * Result of persisting image bytes through {@link LocalImageStorage}. The
 * {@code relativePath} is what the database stores in {@code images.file_path};
 * it is portable (forward slashes) and rooted at the configured local
 * directory rather than the filesystem root, so the same row is reusable if
 * the deployment migrates to a different mount point.
 *
 * @param relativePath path relative to {@code app.images.local-dir}
 * @param sizeBytes    file size on disk in bytes
 * @param width        image width in pixels
 * @param height       image height in pixels
 * @param mimeType     MIME type, currently always {@code image/png}
 */
public record StoredImage(
        String relativePath,
        long sizeBytes,
        int width,
        int height,
        String mimeType
) {
}
