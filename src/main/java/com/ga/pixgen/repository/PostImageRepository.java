package com.ga.pixgen.repository;

import com.ga.pixgen.model.PostImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PostImageRepository extends JpaRepository<PostImage, Long> {

    List<PostImage> findByPost_IdOrderBySortOrderAsc(Long postId);

    List<PostImage> findByPost_IdInOrderByPost_IdAscSortOrderAsc(Collection<Long> postIds);
}
