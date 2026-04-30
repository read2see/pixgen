package com.ga.pixgen.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ga.pixgen.model.JobStatus;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                        + "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
                // Mirror the dev profile's snake_case Jackson contract so the
                // ErrorResponse field names asserted below match what real
                // clients will see in production.
                "spring.jackson.property-naming-strategy=SNAKE_CASE"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private EmailService emailService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private PostService postService;

    @MockitoBean
    private CommentService commentService;

    @Test
    @WithMockUser
    void validationFailure_returns400_withFieldErrors() throws Exception {
        TestPayload body = new TestPayload("not-an-email", "");

        mockMvc.perform(post("/test-errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/test-errors/validate"))
                .andExpect(jsonPath("$.field_errors.email").exists())
                .andExpect(jsonPath("$.field_errors.name").exists());
    }

    @Test
    @WithMockUser
    void badCredentials_returns401() throws Exception {
        mockMvc.perform(get("/test-errors/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"))
                .andExpect(jsonPath("$.path").value("/test-errors/bad-credentials"));
    }

    @Test
    @WithMockUser
    void accessDenied_returns403() throws Exception {
        mockMvc.perform(get("/test-errors/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.path").value("/test-errors/access-denied"));
    }

    @Test
    @WithMockUser
    void resourceNotFound_returns404() throws Exception {
        mockMvc.perform(get("/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("User with id 42 not found"))
                .andExpect(jsonPath("$.path").value("/test-errors/not-found"));
    }

    @Test
    @WithMockUser
    void emailAlreadyExists_returns409() throws Exception {
        mockMvc.perform(get("/test-errors/email-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email already in use: dup@example.com"));
    }

    @Test
    @WithMockUser
    void unverifiedEmail_returns403() throws Exception {
        mockMvc.perform(get("/test-errors/unverified"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Email is not verified"));
    }

    @Test
    @WithMockUser
    void invalidToken_returns400() throws Exception {
        mockMvc.perform(get("/test-errors/invalid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Token is invalid"));
    }

    @Test
    @WithMockUser
    void expiredToken_returns410() throws Exception {
        mockMvc.perform(get("/test-errors/expired-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"))
                .andExpect(jsonPath("$.message").value("Token has expired"));
    }

    @Test
    @WithMockUser
    void jobNotFound_returns404() throws Exception {
        mockMvc.perform(get("/test-errors/job-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Job with id 7 not found"));
    }

    @Test
    @WithMockUser
    void jobNotCancellable_returns409() throws Exception {
        mockMvc.perform(get("/test-errors/job-not-cancellable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Job 7 cannot be cancelled in status SUCCEEDED"));
    }

    @Test
    @WithMockUser
    void pendingJobLimit_returns429() throws Exception {
        mockMvc.perform(get("/test-errors/pending-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value("Pending job limit reached (max 10)"));
    }

    @Test
    @WithMockUser
    void insufficientCredits_returns402() throws Exception {
        mockMvc.perform(get("/test-errors/insufficient-credits"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.error").value("Payment Required"))
                .andExpect(jsonPath("$.message").value("Insufficient credits: required 1 but only 0 available"));
    }

    @Test
    @WithMockUser
    void unhandledException_returns500_withGenericMessage() throws Exception {
        mockMvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("Unexpected error"))
                .andExpect(jsonPath("$.path").value("/test-errors/boom"));
    }

    @TestConfiguration
    static class TestControllerConfig {
        @Bean
        TestErrorController testErrorController() {
            return new TestErrorController();
        }
    }

    @RestController
    @RequestMapping("/test-errors")
    static class TestErrorController {

        @PostMapping("/validate")
        String validate(@Valid @RequestBody TestPayload payload) {
            return "ok";
        }

        @GetMapping("/bad-credentials")
        String badCredentials() {
            throw new BadCredentialsException("nope");
        }

        @GetMapping("/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ResourceNotFoundException("User", 42L);
        }

        @GetMapping("/email-conflict")
        String emailConflict() {
            throw new EmailAlreadyExistsException("dup@example.com");
        }

        @GetMapping("/unverified")
        String unverified() {
            throw new UnverifiedEmailException("Email is not verified");
        }

        @GetMapping("/invalid-token")
        String invalidToken() {
            throw new InvalidTokenException("Token is invalid");
        }

        @GetMapping("/expired-token")
        String expiredToken() {
            throw new ExpiredTokenException("Token has expired");
        }

        @GetMapping("/job-not-found")
        String jobNotFound() {
            throw new JobNotFoundException(7L);
        }

        @GetMapping("/job-not-cancellable")
        String jobNotCancellable() {
            throw new JobNotCancellableException(7L, JobStatus.SUCCEEDED);
        }

        @GetMapping("/pending-limit")
        String pendingLimit() {
            throw new PendingJobLimitException(10);
        }

        @GetMapping("/insufficient-credits")
        String insufficientCredits() {
            throw new InsufficientCreditsException(1, 0);
        }

        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("kaboom");
        }
    }

    record TestPayload(@Email String email, @NotBlank String name) {
    }
}
