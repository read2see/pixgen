package com.ga.pixgen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ga.pixgen.exception.InsufficientCreditsException;
import com.ga.pixgen.exception.JobNotCancellableException;
import com.ga.pixgen.exception.JobNotFoundException;
import com.ga.pixgen.exception.PendingJobLimitException;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
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
import com.ga.pixgen.service.jobs.JobEventBroker;
import com.ga.pixgen.service.jobs.JobService;
import com.ga.pixgen.service.jobs.JobSubmission;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for {@link JobController}.
 *
 * <p>The controller is exercised through the real Spring MVC pipeline so
 * we cover content negotiation, JSON serialisation, validation, the
 * {@code @PreAuthorize} filter chain and the SSE async dispatch wiring.
 * Persistence layers are excluded from the context so the test runs in
 * milliseconds; everything below the controller is either a real
 * infrastructure bean (security filter chain, exception handler) or a
 * Mockito double for the surrounding service.</p>
 */
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
class JobControllerTest {

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

    private User authedUser;
    private String authCookieValue;

    @BeforeEach
    void setUp() {
        authedUser = userWithPermissions(EMAIL, "USER", "job.create", "job.read", "job.cancel");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(authedUser));
        authCookieValue = jwtService.generateToken(EMAIL);
    }

    @Test
    void createJob_returns201_andJobResponse_onHappyPath() throws Exception {
        Job persisted = sampleJob(900L, authedUser.getId(), JobStatus.PENDING);
        when(jobService.submit(eq(authedUser), any(JobSubmission.class))).thenReturn(persisted);

        Map<String, Object> body = Map.of(
                "prompt", "a sunset over the ocean",
                "width", 512,
                "height", 512,
                "steps", 20,
                "cfgScale", 7.5,
                "seed", 42,
                "sampler", "euler-a",
                "modelName", "sd-1.5");

        mockMvc.perform(post("/api/jobs")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(900))
                .andExpect(jsonPath("$.userId").value(authedUser.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.prompt").value("a sunset over the ocean"))
                .andExpect(jsonPath("$.progress").value(0));
    }

    @Test
    void createJob_returns400_whenPromptMissing() throws Exception {
        Map<String, Object> body = Map.of("width", 512, "height", 512);

        mockMvc.perform(post("/api/jobs")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).submit(any(), any());
    }

    @Test
    void createJob_returns401_whenUnauthenticated() throws Exception {
        Map<String, Object> body = Map.of("prompt", "anything");

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createJob_returns403_whenCallerLacksJobCreatePermission() throws Exception {
        User reader = userWithPermissions("reader@pixgen.local", "USER", "job.read");
        when(userRepository.findByEmail(reader.getEmail())).thenReturn(Optional.of(reader));
        String token = jwtService.generateToken(reader.getEmail());

        Map<String, Object> body = Map.of("prompt", "anything");

        mockMvc.perform(post("/api/jobs")
                        .cookie(new Cookie("pixgen_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(jobService, never()).submit(any(), any());
    }

    @Test
    void createJob_returns402_whenSubmitThrowsInsufficientCredits() throws Exception {
        when(jobService.submit(eq(authedUser), any(JobSubmission.class)))
                .thenThrow(new InsufficientCreditsException(1, 0));

        Map<String, Object> body = Map.of("prompt", "anything");

        mockMvc.perform(post("/api/jobs")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void createJob_returns429_whenSubmitThrowsPendingJobLimit() throws Exception {
        when(jobService.submit(eq(authedUser), any(JobSubmission.class)))
                .thenThrow(new PendingJobLimitException(10));

        Map<String, Object> body = Map.of("prompt", "anything");

        mockMvc.perform(post("/api/jobs")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void getJob_returns200_andJobResponse() throws Exception {
        Job job = sampleJob(123L, authedUser.getId(), JobStatus.RUNNING);
        when(jobService.get(eq(123L), eq(authedUser))).thenReturn(job);

        mockMvc.perform(get("/api/jobs/123")
                        .cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(123))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.userId").value(authedUser.getId()));
    }

    @Test
    void getJob_returns404_whenServiceThrowsJobNotFound() throws Exception {
        when(jobService.get(eq(404L), eq(authedUser)))
                .thenThrow(new JobNotFoundException(404L));

        mockMvc.perform(get("/api/jobs/404")
                        .cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getJob_returns403_whenServiceThrowsAccessDenied() throws Exception {
        when(jobService.get(eq(7L), eq(authedUser)))
                .thenThrow(new AccessDeniedException("nope"));

        mockMvc.perform(get("/api/jobs/7")
                        .cookie(authCookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMine_returns200_andSerialisedArray() throws Exception {
        Job a = sampleJob(1L, authedUser.getId(), JobStatus.PENDING);
        Job b = sampleJob(2L, authedUser.getId(), JobStatus.SUCCEEDED);
        when(jobService.listMine(eq(authedUser), isNull())).thenReturn(List.of(a, b));

        mockMvc.perform(get("/api/jobs/me")
                        .cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].status").value("SUCCEEDED"));
    }

    @Test
    void listMine_filtersByStatus_whenQueryParamPresent() throws Exception {
        Job a = sampleJob(1L, authedUser.getId(), JobStatus.PENDING);
        when(jobService.listMine(eq(authedUser), eq(JobStatus.PENDING)))
                .thenReturn(List.of(a));

        mockMvc.perform(get("/api/jobs/me")
                        .cookie(authCookie())
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(jobService).listMine(eq(authedUser), eq(JobStatus.PENDING));
    }

    @Test
    void cancelJob_returns204_onHappyPath() throws Exception {
        mockMvc.perform(post("/api/jobs/77/cancel")
                        .cookie(authCookie()))
                .andExpect(status().isNoContent());

        verify(jobService).cancel(eq(77L), eq(authedUser));
    }

    @Test
    void cancelJob_returns409_whenServiceThrowsNotCancellable() throws Exception {
        doThrow(new JobNotCancellableException(8L, JobStatus.SUCCEEDED))
                .when(jobService).cancel(eq(8L), eq(authedUser));

        mockMvc.perform(post("/api/jobs/8/cancel")
                        .cookie(authCookie()))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelJob_returns404_whenServiceThrowsNotFound() throws Exception {
        doThrow(new JobNotFoundException(9L))
                .when(jobService).cancel(eq(9L), eq(authedUser));

        mockMvc.perform(post("/api/jobs/9/cancel")
                        .cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelJob_returns403_whenCallerLacksJobCancelPermission() throws Exception {
        User reader = userWithPermissions("reader@pixgen.local", "USER", "job.create", "job.read");
        when(userRepository.findByEmail(reader.getEmail())).thenReturn(Optional.of(reader));
        String token = jwtService.generateToken(reader.getEmail());

        mockMvc.perform(post("/api/jobs/77/cancel")
                        .cookie(new Cookie("pixgen_token", token)))
                .andExpect(status().isForbidden());

        verify(jobService, never()).cancel(any(), any());
    }

    @Test
    void streamAll_registersBrokerEmitterForCaller_andStartsAsyncDispatch() throws Exception {
        when(jobEventBroker.register(authedUser.getId())).thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(get("/api/jobs/stream")
                        .cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(jobEventBroker).register(authedUser.getId());
    }

    @Test
    void streamAll_returns403_whenCallerLacksJobReadPermission() throws Exception {
        User stranger = userWithPermissions("stranger@pixgen.local", "USER", "image.read");
        when(userRepository.findByEmail(stranger.getEmail())).thenReturn(Optional.of(stranger));
        String token = jwtService.generateToken(stranger.getEmail());

        mockMvc.perform(get("/api/jobs/stream")
                        .cookie(new Cookie("pixgen_token", token))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());

        verify(jobEventBroker, never()).register(any());
    }

    @Test
    void streamJob_registersBrokerEmitterScopedToJobId_afterOwnershipCheck() throws Exception {
        Job job = sampleJob(55L, authedUser.getId(), JobStatus.RUNNING);
        when(jobService.get(eq(55L), eq(authedUser))).thenReturn(job);
        when(jobEventBroker.register(authedUser.getId(), 55L)).thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(get("/api/jobs/55/stream")
                        .cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(jobService).get(eq(55L), eq(authedUser));
        verify(jobEventBroker).register(authedUser.getId(), 55L);
    }

    private Cookie authCookie() {
        return new Cookie("pixgen_token", authCookieValue);
    }

    private static Job sampleJob(Long id, Long userId, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(userId);
        job.setStatus(status);
        job.setPrompt("a sunset over the ocean");
        job.setNegativePrompt(null);
        job.setWidth(512);
        job.setHeight(512);
        job.setSteps(20);
        job.setCfgScale(7.5);
        job.setSeed(42L);
        job.setSampler("euler-a");
        job.setModelName("sd-1.5");
        job.setCreditsCost(1);
        job.setProgress(status == JobStatus.SUCCEEDED ? 100 : 0);
        Instant now = Instant.parse("2026-04-13T12:00:00Z");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
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
