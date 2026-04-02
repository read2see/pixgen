package com.ga.pixgen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ga.pixgen.dto.LoginRequest;
import com.ga.pixgen.dto.RegisterRequest;
import com.ga.pixgen.dto.SendVerificationRequest;
import com.ga.pixgen.exception.ExpiredTokenException;
import com.ga.pixgen.exception.InvalidTokenException;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.PermissionRepository;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.security.JwtService;
import com.ga.pixgen.service.EmailService;
import com.ga.pixgen.service.EmailVerificationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
class AuthControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private PermissionRepository permissionRepository;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private EmailService emailService;

    @Test
    void register_createsUser_andReturns201_withUserResponse() throws Exception {
        Role role = sampleRole();
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role));
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return user;
        });

        RegisterRequest body = new RegisterRequest("alice@example.com", "Password1!", "alice");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.username").value("alice"));

        verify(emailVerificationService).issueToken("alice@example.com");
    }

    @Test
    void sendVerification_returns202_andDelegatesToService() throws Exception {
        SendVerificationRequest body = new SendVerificationRequest("alice@example.com");

        mockMvc.perform(post("/api/auth/send-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());

        verify(emailVerificationService).issueToken("alice@example.com");
    }

    @Test
    void sendVerification_returns202_evenWhenServiceIsNoop() throws Exception {
        when(emailVerificationService.issueToken("ghost@example.com")).thenReturn(null);
        SendVerificationRequest body = new SendVerificationRequest("ghost@example.com");

        mockMvc.perform(post("/api/auth/send-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    void verifyEmail_returns200_withVerifiedTrue_onHappyPath() throws Exception {
        UUID token = UUID.randomUUID();

        mockMvc.perform(get("/api/auth/verify-email").param("token", token.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        verify(emailVerificationService).verify(eq(token));
    }

    @Test
    void verifyEmail_returns400_whenTokenIsInvalid() throws Exception {
        UUID token = UUID.randomUUID();
        doThrow(new InvalidTokenException("invalid"))
                .when(emailVerificationService).verify(token);

        mockMvc.perform(get("/api/auth/verify-email").param("token", token.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyEmail_returns410_whenTokenIsExpired() throws Exception {
        UUID token = UUID.randomUUID();
        doThrow(new ExpiredTokenException("expired"))
                .when(emailVerificationService).verify(token);

        mockMvc.perform(get("/api/auth/verify-email").param("token", token.toString()))
                .andExpect(status().isGone());
    }

    @Test
    void login_returns200_andSetsHttpOnlyPixgenTokenCookie() throws Exception {
        String email = "alice@example.com";
        User user = sampleUser(email, "Password1!");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        LoginRequest body = new LoginRequest(email, "Password1!");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(cookie().exists("pixgen_token"))
                .andExpect(cookie().httpOnly("pixgen_token", true))
                .andExpect(cookie().path("pixgen_token", "/"))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .as("login cookie must declare SameSite=Lax")
                .contains("SameSite=Lax");
        String tokenValue = result.getResponse().getCookie("pixgen_token").getValue();
        assertThat(tokenValue).isNotBlank();
        assertThat(jwtService.extractSubject(tokenValue)).isEqualTo(email);
    }

    @Test
    void login_returns401_whenCredentialsDoNotMatch() throws Exception {
        String email = "alice@example.com";
        User user = sampleUser(email, "Password1!");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        LoginRequest body = new LoginRequest(email, "WrongPw!");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_clearsPixgenTokenCookie_andReturns204() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("pixgen_token", 0))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .as("logout cookie must clear pixgen_token")
                .contains("pixgen_token=")
                .contains("Max-Age=0");
    }

    @Test
    void me_returns401_whenNoCookiePresent() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns200_andUserResponse_whenValidPixgenTokenCookiePresent() throws Exception {
        String email = "alice@example.com";
        User user = sampleUser(email, "Password1!");
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        String token = jwtService.generateToken(email);

        mockMvc.perform(get("/api/auth/me")
                        .cookie(new Cookie("pixgen_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    private Role sampleRole() {
        Role role = new Role();
        role.setId(1L);
        role.setName("USER");
        return role;
    }

    private User sampleUser(String email, String rawPassword) {
        User user = new User();
        user.setId(42L);
        user.setEmail(email);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(sampleRole());
        user.setEnabled(true);
        user.setVerified(true);
        user.setCredits(0);
        return user;
    }
}
