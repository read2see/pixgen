package com.ga.pixgen.service.profile;

import com.ga.pixgen.config.ProfileImagesProperties;
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
 * Tests for {@link ProfileImageStorage}: user-scoped layout, traversal guard,
 * idempotent deletes, and distinct filenames per write.
 */
class ProfileImageStorageTest {

    @TempDir
    Path tempDir;

    private ProfileImageStorage storage;

    @BeforeEach
    void setUp() {
        ProfileImagesProperties props = new ProfileImagesProperties();
        props.setLocalDir(tempDir.toString());
        storage = new ProfileImageStorage(props);
    }

    @Test
    void write_persistsBytesUnderUserScopedDirectory() throws IOException {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};

        StoredProfileImage stored = storage.write(42L, payload, "image/png");

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
    void write_returnsStoredProfileImageWithSizeAndMime() {
        byte[] payload = new byte[]{9, 9, 9};

        StoredProfileImage stored = storage.write(7L, payload, "image/jpeg");

        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void write_relativePathIsScopedByUserAndUsesForwardSlashes() {
        StoredProfileImage stored = storage.write(99L, new byte[]{1}, "image/webp");

        assertThat(stored.relativePath())
                .as("relative path must be user-scoped and forward-slash separated for portability")
                .startsWith("99/")
                .endsWith(".webp")
                .doesNotContain("\\");
    }

    @Test
    void write_pngUsesPngExtension() {
        StoredProfileImage stored = storage.write(3L, new byte[]{0}, "image/png");

        assertThat(stored.relativePath()).endsWith(".png");
        assertThat(stored.mimeType()).isEqualTo("image/png");
    }

    @Test
    void write_createsUserDirectoryOnFirstUse() {
        Path userDir = tempDir.resolve("123");
        assertThat(Files.exists(userDir))
                .as("precondition: user directory must not exist before the first write")
                .isFalse();

        storage.write(123L, new byte[]{1}, "image/png");

        assertThat(Files.isDirectory(userDir)).isTrue();
    }

    @Test
    void write_producesDistinctFilesForRepeatedCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            StoredProfileImage stored = storage.write(1L, new byte[]{1}, "image/jpeg");
            seen.add(stored.relativePath());
        }
        assertThat(seen)
                .as("each write must produce a unique relative path so we never clobber prior files")
                .hasSize(5);
    }

    @Test
    void delete_removesPreviouslyWrittenFile() throws IOException {
        StoredProfileImage stored = storage.write(5L, new byte[]{1, 2}, "image/png");
        Path resolved = storage.resolve(stored.relativePath());
        assertThat(Files.exists(resolved)).isTrue();

        storage.delete(stored.relativePath());

        assertThat(Files.exists(resolved)).isFalse();
    }

    @Test
    void delete_isIdempotent_whenFileAlreadyMissing() {
        StoredProfileImage stored = storage.write(5L, new byte[]{1}, "image/png");
        storage.delete(stored.relativePath());

        storage.delete(stored.relativePath()); // must not throw
    }

    @Test
    void resolve_rejectsPathTraversalEscape() {
        assertThatThrownBy(() -> storage.resolve("../escape.jpg"))
                .as("resolve must refuse paths that escape the configured root")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void write_rejectsUnknownContentType() {
        assertThatThrownBy(() -> storage.write(1L, new byte[]{1}, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_throwsForUnknownPaths_whenAccessed() {
        Path resolved = storage.resolve("99/missing.png");
        assertThatThrownBy(() -> Files.readAllBytes(resolved))
                .isInstanceOf(NoSuchFileException.class);
    }
}
