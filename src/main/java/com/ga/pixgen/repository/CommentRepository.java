package com.ga.pixgen.repository;

import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long>, JpaSpecificationExecutor<Comment> {

    List<Comment> findByPostIdAndStatusAndDeletedAtIsNullOrderByPathAsc(Long postId, CommentStatus status);
}
