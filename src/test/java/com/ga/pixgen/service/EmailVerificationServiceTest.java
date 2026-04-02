package com.ga.pixgen.service;

import com.ga.pixgen.exception.ExpiredTokenException;
import com.ga.pixgen.exception.InvalidTokenException;
import com.ga.pixgen.model.Token;
import com.ga.pixgen.model.TokenType;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.TokenRepository;
import com.ga.pixgen.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final long TTL_MINUTES = 1440L;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ttlMinutes", TTL_MINUTES);
    }

    @Test
    void issueToken_savesEmailVerificationToken_andSendsEmail() {
        String email = "alice@example.com";
        User user = newUser(email, false);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(Token.class))).thenAnswer(inv -> inv.getArgument(0));

        Token issued = service.issueToken(email);

        ArgumentCaptor<Token> captor = ArgumentCaptor.forClass(Token.class);
        verify(tokenRepository).save(captor.capture());
        Token saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getType()).isEqualTo(TokenType.EMAIL_VERIFICATION);
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(TTL_MINUTES - 1, ChronoUnit.MINUTES));
        verify(emailService).sendVerificationEmail(email, saved.getId());
        assertThat(issued).isSameAs(saved);
    }

    @Test
    void issueToken_isNoop_whenUserNotFound() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        Token issued = service.issueToken("ghost@example.com");

        assertThat(issued).isNull();
        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void issueToken_isNoop_whenUserAlreadyVerified() {
        User user = newUser("alice@example.com", true);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        Token issued = service.issueToken("alice@example.com");

        assertThat(issued).isNull();
        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void verify_marksTokenUsed_andUserVerified_onHappyPath() {
        String email = "alice@example.com";
        User user = newUser(email, false);
        Token token = newValidToken(email, TokenType.EMAIL_VERIFICATION);
        when(tokenRepository.findById(token.getId())).thenReturn(Optional.of(token));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        service.verify(token.getId());

        assertThat(token.isUsed()).isTrue();
        assertThat(user.isVerified()).isTrue();
        verify(tokenRepository).save(token);
        verify(userRepository).save(user);
    }

    @Test
    void verify_throwsExpired_whenTokenIsExpired() {
        Token expired = newValidToken("alice@example.com", TokenType.EMAIL_VERIFICATION);
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(tokenRepository.findById(expired.getId())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.verify(expired.getId()))
                .isInstanceOf(ExpiredTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void verify_throwsInvalid_whenTokenAlreadyUsed() {
        Token used = newValidToken("alice@example.com", TokenType.EMAIL_VERIFICATION);
        used.setUsed(true);
        when(tokenRepository.findById(used.getId())).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.verify(used.getId()))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void verify_throwsInvalid_whenTokenIsWrongType() {
        Token wrongType = newValidToken("alice@example.com", TokenType.PASSWORD_RESET);
        when(tokenRepository.findById(wrongType.getId())).thenReturn(Optional.of(wrongType));

        assertThatThrownBy(() -> service.verify(wrongType.getId()))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void verify_throwsInvalid_whenTokenNotFound() {
        UUID id = UUID.randomUUID();
        when(tokenRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(id))
                .isInstanceOf(InvalidTokenException.class);
    }

    private Token newValidToken(String email, TokenType type) {
        Token token = new Token();
        token.setId(UUID.randomUUID());
        token.setEmail(email);
        token.setType(type);
        Instant now = Instant.now();
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(60, ChronoUnit.MINUTES));
        token.setUsed(false);
        return token;
    }

    private User newUser(String email, boolean verified) {
        User user = new User();
        user.setId(7L);
        user.setEmail(email);
        user.setVerified(verified);
        user.setEnabled(true);
        return user;
    }
}
