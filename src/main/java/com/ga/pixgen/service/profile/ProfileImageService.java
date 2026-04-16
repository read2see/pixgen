package com.ga.pixgen.service.profile;

import com.ga.pixgen.exception.InvalidProfileImageException;
import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProfileImageService {

    private final UserRepository userRepository;
    private final ProfileImageStorage profileImageStorage;

    /**
     * Writes a new profile image for the user, replaces {@code profile_img}, and deletes the prior file when present.
     *
     * @return relative storage path persisted on the user row
     */
    @Transactional
    public String replaceProfileImage(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidProfileImageException("Profile image file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw new InvalidProfileImageException("Content type is required");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        if (bytes.length == 0) {
            throw new InvalidProfileImageException("Profile image file is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        StoredProfileImage stored;
        try {
            stored = profileImageStorage.write(userId, bytes, contentType);
        } catch (IllegalArgumentException ex) {
            throw new InvalidProfileImageException(ex.getMessage());
        }

        String oldRelative = user.getProfileImg();
        user.setProfileImg(stored.relativePath());
        userRepository.save(user);

        if (oldRelative != null && !oldRelative.isBlank()
                && !oldRelative.equals(stored.relativePath())) {
            profileImageStorage.delete(oldRelative);
        }
        return stored.relativePath();
    }

    /**
     * Resolves the on-disk profile image for the authenticated user.
     *
     * @throws ResourceNotFoundException when unset or missing on disk
     */
    public ProfileImageAsset getProfileImageFile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        String relative = user.getProfileImg();
        if (relative == null || relative.isBlank()) {
            throw new ResourceNotFoundException("Profile image", userId);
        }
        Path path = profileImageStorage.resolve(relative);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ResourceNotFoundException("Profile image file", userId);
        }
        MediaType mediaType = mediaTypeForStoredPath(relative, path);
        return new ProfileImageAsset(path, mediaType);
    }

    private static MediaType mediaTypeForStoredPath(String relativePath, Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    public record ProfileImageAsset(Path path, MediaType mediaType) {
    }
}
