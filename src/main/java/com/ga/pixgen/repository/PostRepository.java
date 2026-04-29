package com.ga.pixgen.repository;

import com.ga.pixgen.model.Post;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    Page<Post> findByStatusAndVisibilityAndDeletedAtIsNull(PostStatus status,
                                                           PostVisibility visibility,
                                                           Pageable pageable);

    Optional<Post> findByIdAndStatusAndVisibilityAndDeletedAtIsNull(Long id,
                                                                    PostStatus status,
                                                                    PostVisibility visibility);
}
