package com.ga.pixgen.controller;

import com.ga.pixgen.dto.ImageMetadataDto;
import com.ga.pixgen.dto.ImageResponse;
import com.ga.pixgen.model.Image;
import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.service.images.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Public HTTP surface for {@link ImageService}.
 *
 * <p>All endpoints require {@code image.read}; ownership and
 * privileged-role checks live in the service layer so non-HTTP callers
 * cannot bypass them. The byte endpoint streams the file directly via
 * {@link FileSystemResource} so large generations don't have to be
 * loaded into the JVM heap before the wire flush.</p>
 */
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('image.read')")
    public List<ImageResponse> listMine(@AuthenticationPrincipal CustomUserDetails principal) {
        return imageService.listMine(principal.getUser()).stream()
                .map(ImageResponse::fromEntity)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('image.read')")
    public ImageResponse get(@PathVariable Long id,
                             @AuthenticationPrincipal CustomUserDetails principal) {
        Image image = imageService.get(id, principal.getUser());
        ImageMetadataDto metadata = imageService.getMetadata(id)
                .map(ImageMetadataDto::fromEntity)
                .orElse(null);
        return ImageResponse.fromEntity(image, metadata);
    }

    @GetMapping("/{id}/file")
    @PreAuthorize("hasAuthority('image.read')")
    public ResponseEntity<Resource> file(@PathVariable Long id,
                                         @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        Image image = imageService.get(id, principal.getUser());
        Path path = imageService.resolveFile(image);
        FileSystemResource body = new FileSystemResource(path);
        MediaType contentType = image.getMimeType() != null
                ? MediaType.parseMediaType(image.getMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(body.contentLength())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(body);
    }
}
