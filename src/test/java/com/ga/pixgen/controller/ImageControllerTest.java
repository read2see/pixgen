package com.ga.pixgen.controller;

import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.ImageMetadata;
import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageMetadataRepository;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.repository.PermissionRepository;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.security.JwtService;
import com.ga.pixgen.service.EmailService;
import com.ga.pixgen.service.EmailVerificationService;
import com.ga.pixgen.service.PasswordResetService;
import com.ga.pixgen.service.images.LocalImageStorage;
import com.ga.pixgen.service.jobs.JobEventBroker;
import com.ga.pixgen.service.jobs.JobService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for {@link ImageController}.
 *
 * <p>Exercises the metadata listing/lookup endpoints and the byte-streaming
 * {@code /api/images/{id}/file} endpoint. Permission gates and ownership
 * checks are pinned here so a regression in the controller — or in the way
 * the service layer surfaces ownership failures — fails loudly.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
                "app.images.local-dir=target/test-image-controller-storage"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageControllerTest {

    private static final String OWNER_EMAIL = "owner@pixgen.local";
    private static final String OTHER_EMAIL = "stranger@pixgen.local";
    private static final String ADMIN_EMAIL = "admin@pixgen.local";
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private LocalImageStorage storage;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private PermissionRepository permissionRepository;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private ImageMetadataRepository imageMetadataRepository;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private JobEventBroker jobEventBroker;

    private User owner;
    private User other;
    private User admin;

    @BeforeEach
    void setUp() {
        owner = userWithPermissions(OWNER_EMAIL, 1L, "USER", "image.read");
        other = userWithPermissions(OTHER_EMAIL, 2L, "USER", "image.read");
        admin = userWithPermissions(ADMIN_EMAIL, 3L, "ADMIN", "image.read");
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(userRepository.findByEmail(OTHER_EMAIL)).thenReturn(Optional.of(other));
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(admin));
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        Path root = Path.of("target/test-image-controller-storage");
        if (Files.exists(root)) {
            try (var stream = Files.walk(root)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    @Test
    void listMine_returns200_andOnlyOwnersImages() throws Exception {
        Image one = sampleImage(101L, owner.getId(), 5L, "owner/one.png");
        Image two = sampleImage(102L, owner.getId(), 6L, "owner/two.png");
        when(imageRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()))
                .thenReturn(List.of(one, two));

        mockMvc.perform(get("/api/images/me")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].userId").value(owner.getId()))
                .andExpect(jsonPath("$[0].mimeType").value("image/png"))
                .andExpect(jsonPath("$[1].id").value(102));

        verify(imageRepository).findByUserIdOrderByCreatedAtDesc(owner.getId());
    }

    @Test
    void listMine_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/images/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listMine_returns403_whenCallerLacksImageReadPermission() throws Exception {
        User noPerms = userWithPermissions("noperms@pixgen.local", 99L, "USER");
        when(userRepository.findByEmail("noperms@pixgen.local")).thenReturn(Optional.of(noPerms));
        String token = jwtService.generateToken("noperms@pixgen.local");

        mockMvc.perform(get("/api/images/me")
                        .cookie(new Cookie("pixgen_token", token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getImage_returns200_withMetadata_whenCallerOwnsImage() throws Exception {
        Image image = sampleImage(101L, owner.getId(), 5L, "owner/one.png");
        ImageMetadata metadata = sampleMetadata(image, "sd-1.5");
        when(imageRepository.findById(101L)).thenReturn(Optional.of(image));
        when(imageMetadataRepository.findByImageId(101L)).thenReturn(Optional.of(metadata));

        mockMvc.perform(get("/api/images/101")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.userId").value(owner.getId()))
                .andExpect(jsonPath("$.metadata.modelName").value("sd-1.5"));
    }

    @Test
    void getImage_returns200_evenWithoutMetadata_present() throws Exception {
        Image image = sampleImage(102L, owner.getId(), 6L, "owner/two.png");
        when(imageRepository.findById(102L)).thenReturn(Optional.of(image));
        when(imageMetadataRepository.findByImageId(102L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/images/102")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(102))
                .andExpect(jsonPath("$.metadata").doesNotExist());
    }

    @Test
    void getImage_returns404_whenImageMissing() throws Exception {
        when(imageRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/images/999")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getImage_returns403_whenCallerIsNotOwnerAndNotPrivileged() throws Exception {
        Image image = sampleImage(101L, owner.getId(), 5L, "owner/one.png");
        when(imageRepository.findById(101L)).thenReturn(Optional.of(image));

        mockMvc.perform(get("/api/images/101")
                        .cookie(authCookie(OTHER_EMAIL)))
                .andExpect(status().isForbidden());

        verify(imageMetadataRepository, never()).findByImageId(any());
    }

    @Test
    void getImage_returns200_whenAdminReadsAnyImage() throws Exception {
        Image image = sampleImage(101L, owner.getId(), 5L, "owner/one.png");
        ImageMetadata metadata = sampleMetadata(image, "sd-1.5");
        when(imageRepository.findById(101L)).thenReturn(Optional.of(image));
        when(imageMetadataRepository.findByImageId(101L)).thenReturn(Optional.of(metadata));

        mockMvc.perform(get("/api/images/101")
                        .cookie(authCookie(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101));
    }

    @Test
    void getImageFile_returns200_withImagePngBody_andContentLengthHeader() throws Exception {
        Image image = sampleImage(101L, owner.getId(), 5L, "owner/owner-one.png");
        when(imageRepository.findById(101L)).thenReturn(Optional.of(image));
        writeOnDisk(image.getFilePath(), PNG_BYTES);

        mockMvc.perform(get("/api/images/101/file")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().longValue("Content-Length", PNG_BYTES.length))
                .andExpect(content().bytes(PNG_BYTES));
    }

    @Test
    void getImageFile_returns404_whenImageRowMissing() throws Exception {
        when(imageRepository.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/images/404/file")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getImageFile_returns404_whenFileMissingOnDisk() throws Exception {
        Image image = sampleImage(123L, owner.getId(), 9L, "owner/missing.png");
        when(imageRepository.findById(123L)).thenReturn(Optional.of(image));
        // File is intentionally not written to disk.

        mockMvc.perform(get("/api/images/123/file")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getImageFile_returns403_whenCallerIsNotOwnerAndNotPrivileged() throws Exception {
        Image image = sampleImage(101L, owner.getId(), 5L, "owner/owner-one.png");
        when(imageRepository.findById(101L)).thenReturn(Optional.of(image));

        mockMvc.perform(get("/api/images/101/file")
                        .cookie(authCookie(OTHER_EMAIL)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getImageFile_returns200_whenAdminReadsAnyImage() throws Exception {
        Image image = sampleImage(101L, owner.getId(), 5L, "owner/admin-read.png");
        when(imageRepository.findById(101L)).thenReturn(Optional.of(image));
        writeOnDisk(image.getFilePath(), PNG_BYTES);

        mockMvc.perform(get("/api/images/101/file")
                        .cookie(authCookie(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
    }

    private Cookie authCookie(String email) {
        return new Cookie("pixgen_token", jwtService.generateToken(email));
    }

    private void writeOnDisk(String relativePath, byte[] bytes) throws IOException {
        Path target = storage.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static Image sampleImage(Long id, Long userId, Long jobId, String relativePath) {
        Image image = new Image();
        image.setId(id);
        image.setUserId(userId);
        image.setPrompt("a cat");
        image.setFilePath(relativePath);
        image.setMimeType("image/png");
        image.setFileSizeBytes((long) PNG_BYTES.length);
        image.setWidth(64);
        image.setHeight(32);
        image.setCreatedAt(Instant.parse("2026-04-13T12:00:00Z"));
        return image;
    }

    private static ImageMetadata sampleMetadata(Image image, String modelName) {
        ImageMetadata metadata = new ImageMetadata();
        metadata.setId(7L);
        metadata.setImage(image);
        metadata.setModelName(modelName);
        metadata.setSampler("euler-a");
        metadata.setSteps(20);
        metadata.setCfgScale(7.5);
        metadata.setSeed(42L);
        return metadata;
    }

    private static User userWithPermissions(String email, long userId, String roleName, String... permissionNames) {
        Set<Permission> permissions = new HashSet<>();
        long permId = 1000L;
        for (String name : permissionNames) {
            Permission permission = new Permission();
            permission.setId(permId++);
            permission.setPermission(name);
            permissions.add(permission);
        }
        Role role = new Role();
        role.setId(roleName.hashCode() & 0xFFL);
        role.setName(roleName);
        role.setPermissions(permissions);

        User user = new User();
        user.setId(userId);
        user.setEmail(email);
        user.setUsername(email.split("@")[0]);
        user.setPassword("ENC");
        user.setEnabled(true);
        user.setVerified(true);
        user.setCredits(10);
        user.setRole(role);
        return user;
    }
}
