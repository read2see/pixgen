package com.ga.pixgen.service.images;

import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Application-facing entry point for image lookups and file streaming.
 *
 * <p>Mirrors the ownership model used by {@code JobService}: the row
 * owner sees their own images, {@code ADMIN} and {@code MODERATOR} can
 * read any row. Storage paths are resolved through {@link LocalImageStorage}
 * so the controller never sees absolute filesystem paths and the
 * traversal guard cannot be bypassed.</p>
 */
@Service
public class ImageService {

    static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "MODERATOR");

    private final ImageRepository imageRepository;
    private final LocalImageStorage storage;

    public ImageService(ImageRepository imageRepository,
                        LocalImageStorage storage) {
        this.imageRepository = imageRepository;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public List<Image> listMine(User actor) {
        return imageRepository.findByUserIdOrderByCreatedAtDesc(actor.getId());
    }

    /**
     * Look up image {@code id}, enforcing ownership or privileged-role
     * access. Mirrors {@code JobService.get} so behaviour is consistent
     * across resources.
     *
     * @param id the id value
     * @param actor the actor value
     * @return the Image result
     */
    @Transactional(readOnly = true)
    public Image get(Long id, User actor) {
        Image image = imageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Image", id));
        if (!canAccess(actor, image)) {
            throw new AccessDeniedException("Not allowed to access image " + id);
        }
        return image;
    }

    /**
     * Resolve the on-disk location of {@code image}, throwing
     * {@link ResourceNotFoundException} if the row references a file that
     * is not present on disk (orphaned row, manual cleanup, etc.).
     *
     * @param image the image value
     * @return the Path result
     */
    public Path resolveFile(Image image) {
        Path resolved = storage.resolve(image.getFilePath());
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new ResourceNotFoundException("Image file", image.getId());
        }
        return resolved;
    }

    private static boolean canAccess(User actor, Image image) {
        if (actor == null) {
            return false;
        }
        if (actor.getId() != null && actor.getId().equals(image.getUserId())) {
            return true;
        }
        Role role = actor.getRole();
        return role != null && PRIVILEGED_ROLES.contains(role.getName());
    }
}
