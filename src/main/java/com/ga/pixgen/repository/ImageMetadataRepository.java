package com.ga.pixgen.repository;

import com.ga.pixgen.model.ImageMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository for {@link ImageMetadata}. Looked up by
 * {@code image_id} when the API needs to surface generation parameters
 * alongside an image.
 */
@Repository
public interface ImageMetadataRepository extends JpaRepository<ImageMetadata, Long> {

    Optional<ImageMetadata> findByImageId(Long imageId);
}
