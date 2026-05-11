package com.ga.pixgen.service.management;

import com.ga.pixgen.dto.AuthorResponse;
import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.ImageResponse;
import com.ga.pixgen.dto.JobResponse;
import com.ga.pixgen.dto.PostResponse;
import com.ga.pixgen.dto.UserResponse;
import com.ga.pixgen.exception.CommunityValidationException;
import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.Comment;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.Post;
import com.ga.pixgen.model.PostImage;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.CommentRepository;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.repository.PostImageRepository;
import com.ga.pixgen.repository.PostRepository;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ManagementService {

    private static final Set<String> ASSIGNABLE_USER_ROLES = Set.of("ADMIN", "MODERATOR", "USER");

    private final ObjectProvider<UserRepository> userRepository;
    private final ObjectProvider<RoleRepository> roleRepository;
    private final ObjectProvider<JobRepository> jobRepository;
    private final ObjectProvider<PostRepository> postRepository;
    private final ObjectProvider<CommentRepository> commentRepository;
    private final ObjectProvider<ImageRepository> imageRepository;
    private final ObjectProvider<PostImageRepository> postImageRepository;

    @Transactional(readOnly = true)
    public Page<UserResponse> users(String q, String role, Boolean enabled, Boolean deleted, Pageable pageable) {
        return userRepository.getObject().findAll(userSpec(q, role, enabled, deleted), pageable)
                .map(UserResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> jobs(JobStatus status, Long userId, Instant from, Instant to, Pageable pageable) {
        return jobRepository.getObject().findAll(jobSpec(status, userId, from, to), pageable)
                .map(JobResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> posts(PostStatus status,
                                    PostVisibility visibility,
                                    String authorUsername,
                                    Pageable pageable) {
        Page<Post> posts = postRepository.getObject().findAll(postSpec(status, visibility, authorUsername), pageable);
        Map<Long, List<PostImage>> imagesByPostId = imagesByPostId(posts.getContent());
        Map<Long, AuthorResponse> authorsById = authorsById(posts.getContent().stream()
                .map(Post::getUserId)
                .collect(Collectors.toSet()));
        return posts.map(post -> PostResponse.fromEntity(
                post,
                imagesByPostId.getOrDefault(post.getId(), List.of()),
                authorsById.get(post.getUserId())));
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> comments(CommentStatus status,
                                          String authorUsername,
                                          Long postId,
                                          Pageable pageable) {
        Page<Comment> comments = commentRepository.getObject().findAll(commentSpec(status, authorUsername, postId), pageable);
        Map<Long, AuthorResponse> authorsById = authorsById(comments.getContent().stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet()));
        return comments.map(comment -> CommentResponse.fromEntity(comment, authorsById.get(comment.getUserId())));
    }

    @Transactional(readOnly = true)
    public Page<ImageResponse> images(Long userId, Long jobId, Pageable pageable) {
        return imageRepository.getObject().findAll(imageSpec(userId, jobId), pageable)
                .map(ImageResponse::fromEntity);
    }

    @Transactional
    public UserResponse suspendUser(Long id) {
        User user = requireActiveUser(id);
        user.setEnabled(false);
        return UserResponse.fromEntity(userRepository.getObject().save(user));
    }

    @Transactional
    public UserResponse softDeleteUser(Long id) {
        User user = userRepository.getObject().findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        user.setEnabled(false);
        if (user.getDeletedAt() == null) {
            user.setDeletedAt(Instant.now());
        }
        return UserResponse.fromEntity(userRepository.getObject().save(user));
    }

    @Transactional
    public UserResponse changeUserRole(Long id, String roleName) {
        User user = requireActiveUser(id);
        String normalized = normalizeAssignableRole(roleName);
        Role role = roleRepository.getObject().findByName(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Role '" + normalized + "' not found"));
        user.setRole(role);
        return UserResponse.fromEntity(userRepository.getObject().save(user));
    }

    @Transactional
    public PostResponse hidePost(Long id) {
        Post post = postRepository.getObject().findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Post", id));
        post.setStatus(PostStatus.ARCHIVED);
        Post saved = postRepository.getObject().save(post);
        AuthorResponse author = authorsById(Set.of(saved.getUserId())).get(saved.getUserId());
        return PostResponse.fromEntity(
                saved,
                postImageRepository.getObject().findByPost_IdOrderBySortOrderAsc(saved.getId()),
                author);
    }

    @Transactional
    public CommentResponse hideComment(Long id) {
        Comment comment = commentRepository.getObject().findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", id));
        comment.setStatus(CommentStatus.HIDDEN);
        Comment saved = commentRepository.getObject().save(comment);
        AuthorResponse author = authorsById(Set.of(saved.getUserId())).get(saved.getUserId());
        return CommentResponse.fromEntity(saved, author);
    }

    private static Specification<User> userSpec(String q, String role, Boolean enabled, Boolean deleted) {
        return ManagementService.<User>whereAll()
                .and(textSearch(q))
                .and(roleEquals(role))
                .and(enabledEquals(enabled))
                .and(deletedEquals(deleted));
    }

    private static Specification<User> textSearch(String q) {
        if (q == null || q.isBlank()) {
            return whereAll();
        }
        String pattern = "%" + q.toLowerCase(Locale.ROOT).trim() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("username")), pattern),
                cb.like(cb.lower(root.get("email")), pattern));
    }

    private static Specification<User> roleEquals(String role) {
        if (role == null || role.isBlank()) {
            return whereAll();
        }
        return (root, query, cb) -> cb.equal(root.join("role", JoinType.LEFT).get("name"), role);
    }

    private static Specification<User> enabledEquals(Boolean enabled) {
        return enabled == null ? whereAll() : (root, query, cb) -> cb.equal(root.get("enabled"), enabled);
    }

    private static Specification<User> deletedEquals(Boolean deleted) {
        if (Boolean.TRUE.equals(deleted)) {
            return (root, query, cb) -> cb.isNotNull(root.get("deletedAt"));
        }
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    private static Specification<Job> jobSpec(JobStatus status, Long userId, Instant from, Instant to) {
        return ManagementService.<Job>whereAll()
                .and(status == null ? ManagementService.<Job>whereAll() : (root, query, cb) -> cb.equal(root.get("status"), status))
                .and(userId == null ? ManagementService.<Job>whereAll() : (root, query, cb) -> cb.equal(root.get("userId"), userId))
                .and(from == null ? ManagementService.<Job>whereAll() : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("updatedAt"), from))
                .and(to == null ? ManagementService.<Job>whereAll() : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("updatedAt"), to));
    }

    private static Specification<Post> postSpec(PostStatus status, PostVisibility visibility, String authorUsername) {
        return ManagementService.<Post>whereAll()
                .and(status == null ? ManagementService.<Post>whereAll() : (root, query, cb) -> cb.equal(root.get("status"), status))
                .and(visibility == null ? ManagementService.<Post>whereAll() : (root, query, cb) -> cb.equal(root.get("visibility"), visibility))
                .and(authorUsernameEquals(authorUsername, Post.class));
    }

    private static Specification<Comment> commentSpec(CommentStatus status, String authorUsername, Long postId) {
        return ManagementService.<Comment>whereAll()
                .and(status == null ? ManagementService.<Comment>whereAll() : (root, query, cb) -> cb.equal(root.get("status"), status))
                .and(postId == null ? ManagementService.<Comment>whereAll() : (root, query, cb) -> cb.equal(root.get("postId"), postId))
                .and(authorUsernameEquals(authorUsername, Comment.class));
    }

    private static Specification<Image> imageSpec(Long userId, Long jobId) {
        return ManagementService.<Image>whereAll()
                .and(userId == null ? ManagementService.<Image>whereAll() : (root, query, cb) -> cb.equal(root.get("userId"), userId))
                .and(jobId == null ? ManagementService.<Image>whereAll() : (root, query, cb) -> cb.equal(root.join("job", JoinType.LEFT).get("id"), jobId));
    }

    private static <T> Specification<T> authorUsernameEquals(String authorUsername, Class<T> ignoredType) {
        if (authorUsername == null || authorUsername.isBlank()) {
            return whereAll();
        }
        String normalized = authorUsername.toLowerCase(Locale.ROOT).trim();
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            var user = subquery.from(User.class);
            subquery.select(user.get("id"))
                    .where(cb.equal(cb.lower(user.get("username")), normalized));
            return root.get("userId").in(subquery);
        };
    }

    private static <T> Specification<T> whereAll() {
        return (root, query, cb) -> cb.conjunction();
    }

    private User requireActiveUser(Long id) {
        User user = userRepository.getObject().findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        if (user.getDeletedAt() != null) {
            throw new ResourceNotFoundException("User", id);
        }
        return user;
    }

    private static String normalizeAssignableRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new CommunityValidationException("Role is required");
        }
        String normalized = roleName.trim().toUpperCase(Locale.ROOT);
        if (!ASSIGNABLE_USER_ROLES.contains(normalized)) {
            throw new CommunityValidationException("Role must be one of ADMIN, MODERATOR, USER");
        }
        return normalized;
    }

    private Map<Long, List<PostImage>> imagesByPostId(List<Post> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<Long> postIds = posts.stream().map(Post::getId).toList();
        Map<Long, List<PostImage>> grouped = new HashMap<>();
        for (PostImage image : postImageRepository.getObject().findByPost_IdInOrderByPost_IdAscSortOrderAsc(postIds)) {
            grouped.computeIfAbsent(image.getPost().getId(), ignored -> new java.util.ArrayList<>()).add(image);
        }
        return grouped;
    }

    private Map<Long, AuthorResponse> authorsById(Set<Long> userIds) {
        Set<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.getObject().findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, AuthorResponse::fromEntity));
    }
}
