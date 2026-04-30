package com.ga.pixgen.repository;

import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.Post;
import com.ga.pixgen.model.PostImage;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostCommunityPersistenceTest extends AbstractPostgresContainerTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private PostImageRepository postImageRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Test
    void persistsPostWithDefaultsAndSoftDeleteColumn() {
        Post post = newPost(10L, "Gallery", null, null);

        Post saved = em.persistFlushFind(post);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(10L);
        assertThat(saved.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(saved.getVisibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void persistsOrderedPostImages() {
        Image first = em.persistFlushFind(newImage(10L, "u/10/first.png"));
        Image second = em.persistFlushFind(newImage(10L, "u/10/second.png"));
        Post post = em.persistFlushFind(newPost(10L, "Gallery", PostStatus.PUBLISHED, PostVisibility.PUBLIC));
        em.persist(postImage(post, second, 1));
        em.persist(postImage(post, first, 0));
        em.flush();
        em.clear();

        List<PostImage> images = postImageRepository.findByPost_IdOrderBySortOrderAsc(post.getId());

        assertThat(images).extracting(PostImage::getSortOrder).containsExactly(0, 1);
        assertThat(images).extracting(postImage -> postImage.getImage().getId())
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void publicFeedQueryExcludesPrivateDraftAndDeletedPosts() {
        Post visible = em.persistFlushFind(newPost(1L, "Visible", PostStatus.PUBLISHED, PostVisibility.PUBLIC));
        em.persist(newPost(1L, "Draft", PostStatus.DRAFT, PostVisibility.PUBLIC));
        em.persist(newPost(1L, "Private", PostStatus.PUBLISHED, PostVisibility.PRIVATE));
        Post deleted = newPost(1L, "Deleted", PostStatus.PUBLISHED, PostVisibility.PUBLIC);
        deleted.setDeletedAt(Instant.now());
        em.persist(deleted);
        em.flush();

        List<Post> posts = postRepository.findByStatusAndVisibilityAndDeletedAtIsNull(
                        PostStatus.PUBLISHED,
                        PostVisibility.PUBLIC,
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();

        assertThat(posts).extracting(Post::getId).containsExactly(visible.getId());
    }

    @Test
    void ordersVisibleCommentsByPath() {
        Post post = em.persistFlushFind(newPost(1L, "Visible", PostStatus.PUBLISHED, PostVisibility.PUBLIC));
        Comment child = em.persistFlushFind(newComment(post.getId(), 2L, "0.1.3", 2));
        Comment root = em.persistFlushFind(newComment(post.getId(), 1L, "0.1", 1));
        Comment hidden = newComment(post.getId(), 3L, "0.2", 1);
        hidden.setStatus(CommentStatus.HIDDEN);
        em.persist(hidden);

        List<Comment> comments = commentRepository.findByPostIdAndStatusAndDeletedAtIsNullOrderByPathAsc(
                post.getId(),
                CommentStatus.VISIBLE);

        assertThat(comments).extracting(Comment::getId).containsExactly(root.getId(), child.getId());
    }

    private static Post newPost(long userId, String title, PostStatus status, PostVisibility visibility) {
        Post post = new Post();
        post.setUserId(userId);
        post.setTitle(title);
        post.setBody("Body");
        post.setStatus(status);
        post.setVisibility(visibility);
        return post;
    }

    private static Image newImage(long userId, String filePath) {
        Image image = new Image();
        image.setUserId(userId);
        image.setFilePath(filePath);
        image.setMimeType("image/png");
        return image;
    }

    private static PostImage postImage(Post post, Image image, int sortOrder) {
        PostImage postImage = new PostImage();
        postImage.setPost(post);
        postImage.setImage(image);
        postImage.setSortOrder(sortOrder);
        return postImage;
    }

    private static Comment newComment(long postId, long userId, String path, int depth) {
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setPath(path);
        comment.setDepth(depth);
        comment.setBody("Nice");
        comment.setStatus(CommentStatus.VISIBLE);
        return comment;
    }
}
