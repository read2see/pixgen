package com.ga.pixgen.repository;

import com.ga.pixgen.model.Image;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Image} rows. Backs the worker's
 * success-path persistence and the file-streaming endpoint introduced
 * in a later branch.
 */
@Repository
public interface ImageRepository extends JpaRepository<Image, Long> {

    Optional<Image> findByJobId(Long jobId);

    List<Image> findByUserIdOrderByCreatedAtDesc(Long userId);
}
