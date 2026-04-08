package com.ga.pixgen.service.images;

import com.ga.pixgen.config.ImagesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LocalImageStorage}, the filesystem-backed sink used by the
 * stub image generator. The contract is intentionally narrow:
 *
 * <ul>
 *   <li>Bytes land under {@code {localDir}/{userId}/{uuid}.png}.</li>
 *   <li>The returned relative path is portable (forward slashes) and round-trips
 *       through {@link LocalImageStorage#resolve(String)} to an existing file.</li>
 *   <li>Two writes for the same user produce distinct files — never overwrite.</li>
 *   <li>The user directory is auto-created the first time it is used.</li>
 *   <li>{@link LocalImageStorage#delete(String)} removes the file; subsequent
 *       reads of the same relative path fail.</li>
 * </ul>
 */
class LocalImageStorageTest {

    @TempDir
    Path tempDir;

    private LocalImageStorage storage;

    @BeforeEach
    void setUp() {
        ImagesProperties props = new ImagesProperties();
        props.setLocalDir(tempDir.toString());
        storage = new LocalImageStorage(props);
    }

    @Test
    void write_persistsBytesUnderUserScopedDirectory() throws IOException {
        byte[] payload = samplePng();

        StoredImage stored = storage.write(42L, payload, 8, 8);

        Path resolved = storage.resolve(stored.relativePath());
        assertThat(resolved)
                .as("resolve() must return an absolute path inside the configured root")
                .isAbsolute();
        assertThat(resolved.startsWith(tempDir.toAbsolutePath().normalize()))
                .as("resolved path must stay under the configured root")
                .isTrue();
        assertThat(Files.exists(resolved)).isTrue();
        assertThat(Files.readAllBytes(resolved)).containsExactly(payload);
    }

    @Test
    void write_returnsStoredImageWithDimensionsAndSize() {
        byte[] payload = samplePng();

        StoredImage stored = storage.write(7L, payload, 16, 32);

        assertThat(stored.width()).isEqualTo(16);
        assertThat(stored.height()).isEqualTo(32);
        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
        assertThat(stored.mimeType()).isEqualTo("image/png");
    }

    @Test
    void write_relativePathIsScopedByUserAndUsesForwardSlashes() {
        StoredImage stored = storage.write(99L, samplePng(), 4, 4);

        assertThat(stored.relativePath())
                .as("relative path must be user-scoped and forward-slash separated for portability")
                .startsWith("99/")
                .endsWith(".png")
                .doesNotContain("\\");
    }

    @Test
    void write_createsUserDirectoryOnFirstUse() {
        Path userDir = tempDir.resolve("123");
        assertThat(Files.exists(userDir))
                .as("precondition: user directory must not exist before the first write")
                .isFalse();

        storage.write(123L, samplePng(), 4, 4);

        assertThat(Files.isDirectory(userDir)).isTrue();
    }

    @Test
    void write_producesDistinctFilesForRepeatedCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            StoredImage stored = storage.write(1L, samplePng(), 4, 4);
            seen.add(stored.relativePath());
        }
        assertThat(seen)
                .as("each write must produce a unique relative path so we never clobber prior files")
                .hasSize(5);
    }

    @Test
    void delete_removesPreviouslyWrittenFile() {
        StoredImage stored = storage.write(5L, samplePng(), 4, 4);
        Path resolved = storage.resolve(stored.relativePath());
        assertThat(Files.exists(resolved)).isTrue();

        storage.delete(stored.relativePath());

        assertThat(Files.exists(resolved)).isFalse();
    }

    @Test
    void delete_isIdempotent_whenFileAlreadyMissing() {
        // Calling delete twice must not blow up; the worker may invoke it from an
        // error-path finally block after the first invocation has already cleaned up.
        StoredImage stored = storage.write(5L, samplePng(), 4, 4);
        storage.delete(stored.relativePath());

        storage.delete(stored.relativePath()); // must not throw
    }

    @Test
    void resolve_rejectsPathTraversalEscape() {
        assertThatThrownBy(() -> storage.resolve("../escape.png"))
                .as("resolve must refuse paths that escape the configured root")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_throwsForUnknownPaths_whenAccessed() {
        Path resolved = storage.resolve("99/missing.png");
        assertThatThrownBy(() -> Files.readAllBytes(resolved))
                .isInstanceOf(NoSuchFileException.class);
    }

    private static byte[] samplePng() {
        // Tiny synthetic byte payload — the storage layer is byte-agnostic and we
        // don't want the storage tests to depend on ImageIO encoding.
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};
    }
}
