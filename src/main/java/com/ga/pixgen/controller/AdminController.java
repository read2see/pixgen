package com.ga.pixgen.controller;

import com.ga.pixgen.dto.AdminUserRoleRequest;
import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.ImageResponse;
import com.ga.pixgen.dto.JobResponse;
import com.ga.pixgen.dto.PageResponse;
import com.ga.pixgen.dto.PostResponse;
import com.ga.pixgen.dto.UserResponse;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;
import com.ga.pixgen.service.management.ManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ManagementService managementService;

    @GetMapping("/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> ping() {
        return Map.of("pong", true);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> users(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String role,
                                            @RequestParam(required = false) Boolean enabled,
                                            @RequestParam(required = false) Boolean deleted,
                                            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                            Pageable pageable) {
        return PageResponse.from(managementService.users(q, role, enabled, deleted, pageable));
    }

    @PatchMapping("/users/{id}/suspend")
    @PreAuthorize("hasAuthority('user.delete')")
    public UserResponse suspendUser(@PathVariable Long id) {
        return managementService.suspendUser(id);
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user.delete')")
    public UserResponse softDeleteUser(@PathVariable Long id) {
        return managementService.softDeleteUser(id);
    }

    @PatchMapping("/users/{id}/role")
    @PreAuthorize("hasAuthority('role.manage')")
    public UserResponse changeUserRole(@PathVariable Long id,
                                       @RequestBody AdminUserRoleRequest request) {
        return managementService.changeUserRole(id, request != null ? request.role() : null);
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<JobResponse> jobs(@RequestParam(required = false) JobStatus status,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(name = "user_id", required = false) Long userIdSnake,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                          Instant from,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                          Instant to,
                                          @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                          Pageable pageable) {
        return PageResponse.from(managementService.jobs(status, firstNonNull(userId, userIdSnake), from, to, pageable));
    }

    @GetMapping("/posts")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<PostResponse> posts(@RequestParam(required = false) PostStatus status,
                                            @RequestParam(required = false) PostVisibility visibility,
                                            @RequestParam(required = false) String authorUsername,
                                            @RequestParam(name = "author_username", required = false) String authorUsernameSnake,
                                            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                            Pageable pageable) {
        return PageResponse.from(managementService.posts(
                status,
                visibility,
                firstNonBlank(authorUsername, authorUsernameSnake),
                pageable));
    }

    @GetMapping("/comments")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<CommentResponse> comments(@RequestParam(required = false) CommentStatus status,
                                                  @RequestParam(required = false) String authorUsername,
                                                  @RequestParam(name = "author_username", required = false) String authorUsernameSnake,
                                                  @RequestParam(required = false) Long postId,
                                                  @RequestParam(name = "post_id", required = false) Long postIdSnake,
                                                  @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                                  Pageable pageable) {
        return PageResponse.from(managementService.comments(
                status,
                firstNonBlank(authorUsername, authorUsernameSnake),
                firstNonNull(postId, postIdSnake),
                pageable));
    }

    @GetMapping("/images")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<ImageResponse> images(@RequestParam(required = false) Long userId,
                                              @RequestParam(name = "user_id", required = false) Long userIdSnake,
                                              @RequestParam(required = false) Long jobId,
                                              @RequestParam(name = "job_id", required = false) Long jobIdSnake,
                                              @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                              Pageable pageable) {
        return PageResponse.from(managementService.images(
                firstNonNull(userId, userIdSnake),
                firstNonNull(jobId, jobIdSnake),
                pageable));
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
