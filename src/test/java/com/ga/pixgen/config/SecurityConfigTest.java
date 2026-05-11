package com.ga.pixgen.config;

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
import com.ga.pixgen.service.comments.CommentService;
import com.ga.pixgen.service.posts.PostService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

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
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private PostService postService;

    @MockitoBean
    private CommentService commentService;

    @Test
    void securityFilterChainBeanIsExposed() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
    }

    @Test
    void authenticationManagerBeanIsExposed() {
        assertThat(context.getBeansOfType(AuthenticationManager.class)).isNotEmpty();
    }

    @Test
    void publicAuthEndpoints_arePermittedWithoutAuthentication() throws Exception {
        // The auth filter chain must not reject these requests with 403; whether
        // the controller layer ultimately returns 401 (e.g. for missing/invalid
        // creds on login) is a separate concern handled by the controller.
        Role userRole = new Role();
        userRole.setId(1L);
        userRole.setName("USER");
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("POST /api/auth/login must not be rejected by CSRF/access filters")
                        .isNotEqualTo(403));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("POST /api/auth/register must not be rejected by CSRF/access filters")
                        .isNotEqualTo(403));
    }

    @Test
    void csrf_isDisabled_postsWithoutTokenAreNotForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("CSRF must be disabled; POST without token must not return 403")
                        .isNotEqualTo(403));
    }

    @Test
    void cors_allowsConfiguredFrontendOrigin_withCredentials() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                            .isEqualTo("http://localhost:3000");
                    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                            .isEqualTo("true");
                });
    }

    @Test
    void protectedEndpoint_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCreationPolicy_isStateless_noHttpSessionCreated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")).andReturn();
        HttpSession session = result.getRequest().getSession(false);
        assertThat(session)
                .as("Stateless session policy must not create an HttpSession")
                .isNull();
    }
}
