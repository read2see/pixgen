package com.ga.pixgen.security;

import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.repository.PermissionRepository;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.TokenRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.service.EmailService;
import com.ga.pixgen.service.EmailVerificationService;
import com.ga.pixgen.service.PasswordResetService;
import com.ga.pixgen.service.comments.CommentService;
import com.ga.pixgen.service.posts.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class RbacIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private PermissionRepository permissionRepository;

    @MockitoBean
    private TokenRepository tokenRepository;

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
    private PostService postService;

    @MockitoBean
    private CommentService commentService;

    @BeforeEach
    void stubUserLookups() {
        // Used by tests that rely on @WithUserDetails to resolve a user via the
        // real CustomUserDetailsService -> UserRepository.findByEmail path.
        when(userRepository.findByEmail("admin@pixgen.local"))
                .thenReturn(Optional.of(adminUser()));
        when(userRepository.findByEmail("reader@pixgen.local"))
                .thenReturn(Optional.of(readerUser()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPing_returns200_whenCallerHasAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value(true));
    }

    @Test
    @WithMockUser(roles = "USER")
    void adminPing_returns403_whenCallerLacksAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPing_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "user.read")
    void usersMe_returns200_whenCallerHasUserReadPermission() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("user"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void usersMe_returns403_whenCallerLacksUserReadPermission() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersMe_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithUserDetails(value = "admin@pixgen.local", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void customUserDetails_grantsRoleAuthority_forAdminEndpoint() throws Exception {
        // The real CustomUserDetailsService loads the user, CustomUserDetails turns
        // its Role into "ROLE_ADMIN", so hasRole('ADMIN') must succeed.
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(value = "reader@pixgen.local", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void customUserDetails_grantsBarePermissionAuthority_forUserMe() throws Exception {
        // CustomUserDetails must also expose each Permission as a bare authority
        // string (Spatie style), so hasAuthority('user.read') must succeed even
        // though the role is USER (not ADMIN).
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("reader@pixgen.local"));
    }

    @Test
    @WithUserDetails(value = "reader@pixgen.local", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void customUserDetails_doesNotGrantUnrelatedRole() throws Exception {
        // The reader has ROLE_USER + user.read but not ROLE_ADMIN, so the admin
        // endpoint must remain forbidden.
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isForbidden());
    }

    private static User adminUser() {
        Role role = new Role();
        role.setId(1L);
        role.setName("ADMIN");
        role.setPermissions(Set.of());
        User user = new User();
        user.setId(1L);
        user.setEmail("admin@pixgen.local");
        user.setPassword("ENC");
        user.setUsername("admin");
        user.setEnabled(true);
        user.setVerified(true);
        user.setRole(role);
        return user;
    }

    private static User readerUser() {
        Permission userRead = new Permission();
        userRead.setId(10L);
        userRead.setPermission("user.read");

        Set<Permission> perms = new HashSet<>();
        perms.add(userRead);

        Role role = new Role();
        role.setId(2L);
        role.setName("USER");
        role.setPermissions(perms);

        User user = new User();
        user.setId(2L);
        user.setEmail("reader@pixgen.local");
        user.setPassword("ENC");
        user.setUsername("reader");
        user.setEnabled(true);
        user.setVerified(true);
        user.setRole(role);
        return user;
    }
}
