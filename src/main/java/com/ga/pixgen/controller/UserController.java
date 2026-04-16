package com.ga.pixgen.controller;

import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.service.profile.ProfileImageService;
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
import org.springframework.web.bind.annotation.PostMapping;
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

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('user.read')")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", authentication.getName());
        return body;
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
}
