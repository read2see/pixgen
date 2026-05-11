package com.ga.pixgen.service.comments;

import com.ga.pixgen.config.CommentProperties;
import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.CreateCommentRequest;
import com.ga.pixgen.exception.CommunityValidationException;
import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.Post;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.CommentRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.service.posts.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private PostService postService;

    @Mock
    private UserRepository userRepository;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentService(commentRepository, postService, new CommentProperties(3), userRepository);
    }

    @Test
    void createRootCommentGeneratesRootPath() {
        when(postService.getPublicPost(100L)).thenReturn(new Post());
        when(commentRepository.saveAndFlush(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(10L);
            return comment;
        });
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommentResponse response = commentService.create(
                user(42L),
                100L,
                new CreateCommentRequest(null, "Nice"));

        assertThat(response.path()).isEqualTo("0.10");
        assertThat(response.depth()).isEqualTo(1);
        assertThat(response.parentId()).isNull();
    }

    @Test
    void createReplyExtendsParentPath() {
        Comment parent = comment(7L, 100L, "0.7", 1);
        when(postService.getPublicPost(100L)).thenReturn(new Post());
        when(commentRepository.findById(7L)).thenReturn(Optional.of(parent));
        when(commentRepository.saveAndFlush(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(11L);
            return comment;
        });
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommentResponse response = commentService.create(
                user(42L),
                100L,
                new CreateCommentRequest(7L, "Reply"));

        assertThat(response.path()).isEqualTo("0.7.11");
        assertThat(response.depth()).isEqualTo(2);
        assertThat(response.parentId()).isEqualTo(7L);
    }

    @Test
    void createRejectsRepliesBeyondMaxDepth() {
        Comment parent = comment(7L, 100L, "0.1.2", 3);
        when(postService.getPublicPost(100L)).thenReturn(new Post());
        when(commentRepository.findById(7L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> commentService.create(
                user(42L),
                100L,
                new CreateCommentRequest(7L, "Too deep")))
                .isInstanceOf(CommunityValidationException.class)
                .hasMessage("Maximum comment reply depth reached");

        verify(commentRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsParentFromDifferentPost() {
        Comment parent = comment(7L, 101L, "0.7", 1);
        when(postService.getPublicPost(100L)).thenReturn(new Post());
        when(commentRepository.findById(7L)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> commentService.create(
                user(42L),
                100L,
                new CreateCommentRequest(7L, "Wrong post")))
                .isInstanceOf(CommunityValidationException.class)
                .hasMessage("Parent comment belongs to a different post");
    }

    @Test
    void listReturnsVisibleCommentsInRepositoryOrder() {
        Comment root = comment(1L, 100L, "0.1", 1);
        Comment child = comment(2L, 100L, "0.1.2", 2);
        when(postService.getPublicPost(100L)).thenReturn(new Post());
        when(commentRepository.findByPostIdAndStatusAndDeletedAtIsNullOrderByPathAsc(100L, CommentStatus.VISIBLE))
                .thenReturn(List.of(root, child));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(42L, "creator")));

        List<CommentResponse> responses = commentService.list(100L);

        assertThat(responses).extracting(CommentResponse::path).containsExactly("0.1", "0.1.2");
        assertThat(responses).extracting(CommentResponse::username).containsExactly("creator", "creator");
    }

    private static User user(Long id) {
        return user(id, null);
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private static Comment comment(Long id, Long postId, String path, int depth) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setUserId(42L);
        comment.setPath(path);
        comment.setDepth(depth);
        comment.setBody("Body");
        comment.setStatus(CommentStatus.VISIBLE);
        return comment;
    }
}
