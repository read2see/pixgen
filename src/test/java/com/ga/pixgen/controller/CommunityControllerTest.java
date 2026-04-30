package com.ga.pixgen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ga.pixgen.dto.CommentResponse;
import com.ga.pixgen.dto.PostResponse;
import com.ga.pixgen.exception.CommunityValidationException;
import com.ga.pixgen.model.CommentStatus;
import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.PostStatus;
import com.ga.pixgen.model.PostVisibility;
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
import com.ga.pixgen.service.comments.CommentService;
import com.ga.pixgen.service.jobs.JobEventBroker;
import com.ga.pixgen.service.jobs.JobService;
import com.ga.pixgen.service.posts.PostService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunityControllerTest {

    private static final String EMAIL = "creator@pixgen.local";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

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

    @MockitoBean
    private PostService postService;

    @MockitoBean
    private CommentService commentService;

    private User authedUser;
    private String authCookieValue;

    @BeforeEach
    void setUp() {
        authedUser = userWithPermissions(EMAIL, "USER", "post.create", "comment.create");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(authedUser));
        authCookieValue = jwtService.generateToken(EMAIL);
    }

    @Test
    void createPostReturns201ForAuthenticatedCreator() throws Exception {
        when(postService.create(eq(authedUser), any())).thenReturn(postResponse(100L));

        mockMvc.perform(post("/api/posts")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Showcase",
                                "body", "Generated set",
                                "visibility", "PUBLIC",
                                "imageIds", List.of(1, 2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void createPostReturns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Showcase",
                                "imageIds", List.of(1)))))
                .andExpect(status().isUnauthorized());

        verify(postService, never()).create(any(), any());
    }

    @Test
    void createPostReturns403WhenPermissionMissing() throws Exception {
        User reader = userWithPermissions("reader@pixgen.local", "USER", "post.read");
        when(userRepository.findByEmail(reader.getEmail())).thenReturn(Optional.of(reader));

        mockMvc.perform(post("/api/posts")
                        .cookie(new Cookie("pixgen_token", jwtService.generateToken(reader.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Showcase",
                                "imageIds", List.of(1)))))
                .andExpect(status().isForbidden());

        verify(postService, never()).create(any(), any());
    }

    @Test
    void feedIsPublicForAnonymousReaders() throws Exception {
        when(postService.feed(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(postResponse(100L))));

        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(100));
    }

    @Test
    void getPostIsPublicForAnonymousReaders() throws Exception {
        when(postService.getPublic(100L)).thenReturn(postResponse(100L));

        mockMvc.perform(get("/api/posts/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    void createCommentReturns400WhenDepthLimitReached() throws Exception {
        when(commentService.create(eq(authedUser), eq(100L), any()))
                .thenThrow(new CommunityValidationException("Maximum comment reply depth reached"));

        mockMvc.perform(post("/api/posts/100/comments")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", 99,
                                "body", "Reply"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Maximum comment reply depth reached"));
    }

    @Test
    void listCommentsIsPublicAndOrderedByService() throws Exception {
        when(commentService.list(100L)).thenReturn(List.of(commentResponse(1L, "0.1")));

        mockMvc.perform(get("/api/posts/100/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].path").value("0.1"));
    }

    private Cookie authCookie() {
        return new Cookie("pixgen_token", authCookieValue);
    }

    private static PostResponse postResponse(Long id) {
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        return new PostResponse(
                id,
                42L,
                "Showcase",
                "Generated set",
                PostStatus.PUBLISHED,
                PostVisibility.PUBLIC,
                List.of(),
                now,
                now);
    }

    private static CommentResponse commentResponse(Long id, String path) {
        Instant now = Instant.parse("2026-04-30T12:00:00Z");
        return new CommentResponse(
                id,
                100L,
                42L,
                null,
                path,
                1,
                "Nice",
                CommentStatus.VISIBLE,
                now,
                now);
    }

    private static User userWithPermissions(String email, String roleName, String... permissionNames) {
        Set<Permission> permissions = new HashSet<>();
        long permId = 1000L;
        for (String name : permissionNames) {
            Permission permission = new Permission();
            permission.setId(permId++);
            permission.setPermission(name);
            permissions.add(permission);
        }
        Role role = new Role();
        role.setId(7L);
        role.setName(roleName);
        role.setPermissions(permissions);

        User user = new User();
        user.setId(42L);
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
