package com.ga.pixgen.controller;

import com.ga.pixgen.dto.CreatePostRequest;
import com.ga.pixgen.dto.PostResponse;
import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.service.posts.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @PostMapping
    @PreAuthorize("hasAuthority('post.create')")
    public ResponseEntity<PostResponse> create(@AuthenticationPrincipal CustomUserDetails principal,
                                               @Valid @RequestBody CreatePostRequest request) {
        PostResponse response = postService.create(principal.getUser(), request);
        return ResponseEntity.created(URI.create("/api/posts/" + response.id())).body(response);
    }

    @GetMapping
    public Page<PostResponse> feed(@PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                                   Pageable pageable) {
        return postService.feed(pageable);
    }

    @GetMapping("/{id}")
    public PostResponse get(@PathVariable Long id) {
        return postService.getPublic(id);
    }
}
