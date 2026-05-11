package com.ga.pixgen.controller;

import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.ImageResponse;
import com.ga.pixgen.dto.ModerationReasonRequest;
import com.ga.pixgen.dto.PageResponse;
import com.ga.pixgen.dto.PostResponse;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.service.management.ManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/moderator")
@RequiredArgsConstructor
public class ModeratorController {

    private final ManagementService managementService;

    @GetMapping("/posts")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public PageResponse<PostResponse> posts(@RequestParam(required = false) PostStatus status,
                                            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                            Pageable pageable) {
        return PageResponse.from(managementService.posts(status, null, null, pageable));
    }

    @GetMapping("/comments")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public PageResponse<CommentResponse> comments(@RequestParam(required = false) CommentStatus status,
                                                  @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                                  Pageable pageable) {
        return PageResponse.from(managementService.comments(status, null, null, pageable));
    }

    @GetMapping("/images")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public PageResponse<ImageResponse> images(@PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                              Pageable pageable) {
        return PageResponse.from(managementService.images(null, null, pageable));
    }

    @PostMapping("/posts/{id}/hide")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public PostResponse hidePost(@PathVariable Long id,
                                 @Valid @RequestBody(required = false) ModerationReasonRequest request) {
        return managementService.hidePost(id);
    }

    @PostMapping("/comments/{id}/hide")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public CommentResponse hideComment(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) ModerationReasonRequest request) {
        return managementService.hideComment(id);
    }
}
