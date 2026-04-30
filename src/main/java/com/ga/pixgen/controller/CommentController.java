package com.ga.pixgen.controller;

import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.CreateCommentRequest;
import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.service.comments.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.List;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @PostMapping
    @PreAuthorize("hasAuthority('comment.create')")
    public ResponseEntity<CommentResponse> create(@PathVariable Long postId,
                                                  @AuthenticationPrincipal CustomUserDetails principal,
                                                  @Valid @RequestBody CreateCommentRequest request) {
        CommentResponse response = commentService.create(principal.getUser(), postId, request);
        return ResponseEntity.created(URI.create("/api/posts/" + postId + "/comments/" + response.id()))
                .body(response);
    }

    @GetMapping
    public List<CommentResponse> list(@PathVariable Long postId) {
        return commentService.list(postId);
    }
}
