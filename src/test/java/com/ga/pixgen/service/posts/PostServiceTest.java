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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostImageRepository postImageRepository;

    @Mock
    private ImageRepository imageRepository;

    @InjectMocks
    private PostService postService;

    @Test
    void createPublishesPostWithOwnedImagesInRequestOrder() {
        User actor = user(10L);
        Image first = image(1L, 10L);
        Image second = image(2L, 10L);
        when(imageRepository.findAllById(List.of(2L, 1L))).thenReturn(List.of(first, second));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            post.setId(99L);
            return post;
        });
        List<PostImage> savedPostImages = new ArrayList<>();
        when(postImageRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<PostImage> images = invocation.getArgument(0);
            images.forEach(savedPostImages::add);
            return savedPostImages;
        });

        PostResponse response = postService.create(
                actor,
                new CreatePostRequest("My post", "Body", PostVisibility.PUBLIC, List.of(2L, 1L)));

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(response.images()).extracting("id").containsExactly(2L, 1L);
        verify(postImageRepository).saveAll(any());
        assertThat(savedPostImages).extracting(PostImage::getSortOrder).containsExactly(0, 1);
    }

    @Test
    void createRejectsDuplicateImageIds() {
        assertThatThrownBy(() -> postService.create(
                user(10L),
                new CreatePostRequest("My post", "Body", PostVisibility.PUBLIC, List.of(1L, 1L))))
                .isInstanceOf(CommunityValidationException.class)
                .hasMessage("Post image IDs must be unique");

        verify(postRepository, never()).save(any());
    }

    @Test
    void createRejectsImagesOwnedByAnotherUser() {
        when(imageRepository.findAllById(List.of(1L))).thenReturn(List.of(image(1L, 99L)));

        assertThatThrownBy(() -> postService.create(
                user(10L),
                new CreatePostRequest("My post", "Body", PostVisibility.PUBLIC, List.of(1L))))
                .isInstanceOf(AccessDeniedException.class);

        verify(postRepository, never()).save(any());
    }

    @Test
    void getPublicRejectsPrivateOrMissingPosts() {
        when(postRepository.findByIdAndStatusAndVisibilityAndDeletedAtIsNull(
                7L,
                PostStatus.PUBLISHED,
                PostVisibility.PUBLIC)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPublic(7L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Post with id 7 not found");
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Image image(Long id, Long userId) {
        Image image = new Image();
        image.setId(id);
        image.setUserId(userId);
        image.setFilePath("u/" + userId + "/" + id + ".png");
        return image;
    }
}
