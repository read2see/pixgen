package com.ga.pixgen.controller;

import com.ga.pixgen.dto.IncreaseCreditsRequest;
import com.ga.pixgen.dto.UserResponse;
import com.ga.pixgen.dto.UserStatsResponse;
import com.ga.pixgen.model.User;
import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.service.UserCreditService;
import com.ga.pixgen.service.UserStatsService;
import com.ga.pixgen.service.profile.ProfileImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final ProfileImageService profileImageService;
    private final UserCreditService userCreditService;
    private final UserStatsService userStatsService;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('user.read')")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", authentication.getName());
        return body;
    }

    @GetMapping("/me/stats")
    @PreAuthorize("hasAuthority('user.read')")
    public UserStatsResponse stats(@AuthenticationPrincipal CustomUserDetails principal) {
        return userStatsService.getStats(principal.getUser());
    }

    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('user.update')")
    public Map<String, String> uploadProfileImage(@AuthenticationPrincipal CustomUserDetails principal,
                                                  @RequestParam("file") MultipartFile file) {
        String relative = profileImageService.replaceProfileImage(principal.getUser().getId(), file);
        return Map.of("profile_img", relative);
    }

    @GetMapping("/me/profile-image")
    @PreAuthorize("hasAuthority('user.read')")
    public ResponseEntity<Resource> profileImageFile(@AuthenticationPrincipal CustomUserDetails principal)
            throws IOException {
        ProfileImageService.ProfileImageAsset asset = profileImageService
                .getProfileImageFile(principal.getUser().getId());
        FileSystemResource body = new FileSystemResource(asset.path());
        return ResponseEntity.ok()
                .contentType(asset.mediaType())
                .contentLength(body.contentLength())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(body);
    }

    @PostMapping("/{id}/credits/increase")
    @PreAuthorize("hasAuthority('credits.grant')")
    public UserResponse increaseCredits(@PathVariable Long id,
                                        @Valid @RequestBody IncreaseCreditsRequest request) {
        User updated = userCreditService.increaseCredits(id, request.amount());
        return UserResponse.fromEntity(updated);
    }
}
