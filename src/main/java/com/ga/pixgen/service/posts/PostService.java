package com.ga.pixgen.service.posts;

import com.ga.pixgen.dto.CreatePostRequest;
import com.ga.pixgen.dto.PostResponse;
import com.ga.pixgen.exception.CommunityValidationException;
import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.Post;
import com.ga.pixgen.model.PostImage;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.PostImageRepository;
import com.ga.pixgen.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final PostImageRepository postImageRepository;
    private final ImageRepository imageRepository;

    @Transactional
    public PostResponse create(User actor, CreatePostRequest request) {
        List<Long> imageIds = request.imageIds();
        ensureNoDuplicateImages(imageIds);
        Map<Long, Image> imagesById = loadImages(imageIds);
        for (Long imageId : imageIds) {
            Image image = imagesById.get(imageId);
            if (!actor.getId().equals(image.getUserId())) {
                throw new AccessDeniedException("Not allowed to attach image " + imageId);
            }
        }

        Post post = new Post();
        post.setUserId(actor.getId());
        post.setTitle(request.title());
        post.setBody(request.body());
        post.setStatus(PostStatus.PUBLISHED);
        post.setVisibility(request.visibility() != null ? request.visibility() : PostVisibility.PUBLIC);
        Post saved = postRepository.save(post);

        List<PostImage> savedImages = postImageRepository.saveAll(toPostImages(saved, imageIds, imagesById));
        return PostResponse.fromEntity(saved, savedImages);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> feed(Pageable pageable) {
        Page<Post> posts = postRepository.findByStatusAndVisibilityAndDeletedAtIsNull(
                PostStatus.PUBLISHED,
                PostVisibility.PUBLIC,
                pageable);
        Map<Long, List<PostImage>> imagesByPostId = imagesByPostId(posts.getContent());
        return posts.map(post -> PostResponse.fromEntity(
                post,
                imagesByPostId.getOrDefault(post.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public PostResponse getPublic(Long id) {
        Post post = getPublicPost(id);
        return PostResponse.fromEntity(post, postImageRepository.findByPost_IdOrderBySortOrderAsc(post.getId()));
    }

    @Transactional(readOnly = true)
    public Post getPublicPost(Long id) {
        return postRepository.findByIdAndStatusAndVisibilityAndDeletedAtIsNull(
                        id,
                        PostStatus.PUBLISHED,
                        PostVisibility.PUBLIC)
                .orElseThrow(() -> new ResourceNotFoundException("Post", id));
    }

    private static void ensureNoDuplicateImages(List<Long> imageIds) {
        Set<Long> unique = new HashSet<>(imageIds);
        if (unique.size() != imageIds.size()) {
            throw new CommunityValidationException("Post image IDs must be unique");
        }
    }

    private Map<Long, Image> loadImages(List<Long> imageIds) {
        Map<Long, Image> imagesById = imageRepository.findAllById(imageIds).stream()
                .collect(Collectors.toMap(Image::getId, image -> image));
        for (Long imageId : imageIds) {
            if (!imagesById.containsKey(imageId)) {
                throw new ResourceNotFoundException("Image", imageId);
            }
        }
        return imagesById;
    }

    private static List<PostImage> toPostImages(Post post, List<Long> imageIds, Map<Long, Image> imagesById) {
        return imageIds.stream()
                .map(imageId -> {
                    PostImage postImage = new PostImage();
                    postImage.setPost(post);
                    postImage.setImage(imagesById.get(imageId));
                    postImage.setSortOrder(imageIds.indexOf(imageId));
                    return postImage;
                })
                .toList();
    }

    private Map<Long, List<PostImage>> imagesByPostId(List<Post> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<Long> postIds = posts.stream().map(Post::getId).toList();
        Map<Long, List<PostImage>> grouped = new HashMap<>();
        for (PostImage image : postImageRepository.findByPost_IdInOrderByPost_IdAscSortOrderAsc(postIds)) {
            grouped.computeIfAbsent(image.getPost().getId(), ignored -> new java.util.ArrayList<>()).add(image);
        }
        return grouped;
    }
}
