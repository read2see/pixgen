package com.ga.pixgen.service.comments;

import com.ga.pixgen.config.CommentProperties;
import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.CreateCommentRequest;
import com.ga.pixgen.exception.CommunityValidationException;
import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.CommentRepository;
import com.ga.pixgen.service.posts.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentService {

    private static final String MAX_DEPTH_MESSAGE = "Maximum comment reply depth reached";

    private final CommentRepository commentRepository;
    private final PostService postService;
    private final CommentProperties properties;

    @Transactional
    public CommentResponse create(User actor, Long postId, CreateCommentRequest request) {
        postService.getPublicPost(postId);
        Comment parent = parentComment(postId, request.parentId());
        int depth = parent == null ? 1 : parent.getDepth() + 1;
        if (depth > properties.maxDepth()) {
            throw new CommunityValidationException(MAX_DEPTH_MESSAGE);
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(actor.getId());
        comment.setParent(parent);
        comment.setDepth(depth);
        comment.setBody(request.body());
        comment.setStatus(CommentStatus.VISIBLE);

        Comment saved = commentRepository.saveAndFlush(comment);
        saved.setPath(parent == null ? "0." + saved.getId() : parent.getPath() + "." + saved.getId());
        return CommentResponse.fromEntity(commentRepository.save(saved));
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long postId) {
        postService.getPublicPost(postId);
        return commentRepository.findByPostIdAndStatusAndDeletedAtIsNullOrderByPathAsc(postId, CommentStatus.VISIBLE)
                .stream()
                .map(CommentResponse::fromEntity)
                .toList();
    }

    private Comment parentComment(Long postId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        Comment parent = commentRepository.findById(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", parentId));
        if (!postId.equals(parent.getPostId())) {
            throw new CommunityValidationException("Parent comment belongs to a different post");
        }
        if (parent.getDeletedAt() != null || parent.getStatus() != CommentStatus.VISIBLE) {
            throw new ResourceNotFoundException("Comment", parentId);
        }
        return parent;
    }
}
