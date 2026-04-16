package com.ga.pixgen.controller;

import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.repository.PermissionRepository;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.security.JwtService;
import com.ga.pixgen.service.EmailService;
import com.ga.pixgen.service.EmailVerificationService;
import com.ga.pixgen.service.PasswordResetService;
import com.ga.pixgen.service.jobs.JobEventBroker;
import com.ga.pixgen.service.jobs.JobService;
import com.ga.pixgen.service.profile.ProfileImageStorage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for profile image upload and download on {@link UserController}.
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
                "app.images.local-dir=target/test-user-profile-job-images",
                "app.profile-images.local-dir=target/test-user-profile-images"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileImageHttpTest {

    private static final String OWNER_EMAIL = "owner@pixgen.local";
    private static final String NO_UPDATE_EMAIL = "readonly@pixgen.local";
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ProfileImageStorage profileImageStorage;

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
    private User readOnlyProfile;

    @BeforeEach
    void setUp() {
        owner = userWithPermissions(OWNER_EMAIL, 1L, "USER",
                "user.read", "user.update");
        readOnlyProfile = userWithPermissions(NO_UPDATE_EMAIL, 2L, "USER", "user.read");
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(userRepository.findByEmail(NO_UPDATE_EMAIL)).thenReturn(Optional.of(readOnlyProfile));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        wipeDir(Path.of("target/test-user-profile-images"));
        wipeDir(Path.of("target/test-user-profile-job-images"));
    }

    private static void wipeDir(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
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

    @Test
    void postProfileImage_returns200_andPersistsRelativePathOnUser() throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file",
                "avatar.png",
                MediaType.IMAGE_PNG_VALUE,
                PNG_BYTES);

        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(part)
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_img").isString());

        verify(userRepository, times(1)).save(any(User.class));
        assertOwnerHasProfileImageOnDisk();
    }

    @Test
    void getProfileImage_returnsBytes_whenSaved() throws Exception {
        StoredProfileFixture fixture = writeFixtureForOwner();

        mockMvc.perform(get("/api/users/me/profile-image")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", fixture.mime()))
                .andExpect(content().bytes(PNG_BYTES));
    }

    @Test
    void getProfileImage_returns404_whenUserHasNoProfileImage() throws Exception {
        owner.setProfileImg(null);

        mockMvc.perform(get("/api/users/me/profile-image")
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isNotFound());
    }

    @Test
    void postProfileImage_returns401_whenUnauthenticated() throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", "x.png", MediaType.IMAGE_PNG_VALUE, PNG_BYTES);

        mockMvc.perform(multipart("/api/users/me/profile-image").file(part))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postProfileImage_returns403_whenCallerLacksUserUpdate() throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", "x.png", MediaType.IMAGE_PNG_VALUE, PNG_BYTES);

        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(part)
                        .cookie(authCookie(NO_UPDATE_EMAIL)))
                .andExpect(status().isForbidden());
    }

    @Test
    void postProfileImage_returns400_whenContentTypeUnsupported() throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", "x.bin", MediaType.APPLICATION_PDF_VALUE, new byte[]{1});

        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(part)
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postProfileImage_returns400_whenFileEmpty() throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", "empty.png", MediaType.IMAGE_PNG_VALUE, new byte[]{});

        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(part)
                        .cookie(authCookie(OWNER_EMAIL)))
                .andExpect(status().isBadRequest());
    }

    private void assertOwnerHasProfileImageOnDisk() throws IOException {
        String relative = owner.getProfileImg();
        Path path = profileImageStorage.resolve(relative);
        assert Files.exists(path) : "expected profile file on disk: " + path;
        assert Arrays.equals(PNG_BYTES, Files.readAllBytes(path))
                : "bytes on disk must match uploaded payload";
    }

    /** Record + helper mimicking stored profile mime for assertions (fixture only). */
    private record StoredProfileFixture(String mime) {
    }

    private StoredProfileFixture writeFixtureForOwner() throws IOException {
        var stored = profileImageStorage.write(owner.getId(), PNG_BYTES, MediaType.IMAGE_PNG_VALUE);
        owner.setProfileImg(stored.relativePath());
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        return new StoredProfileFixture(stored.mimeType());
    }

    private Cookie authCookie(String email) {
        return new Cookie("pixgen_token", jwtService.generateToken(email));
    }

    private static User userWithPermissions(String email, long userId, String roleName,
                                            String... permissionNames) {
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
