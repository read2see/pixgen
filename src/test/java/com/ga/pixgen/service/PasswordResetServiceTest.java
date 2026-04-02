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
import org.springframework.security.crypto.password.PasswordEncoder;
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
class PasswordResetServiceTest {

    private static final long TTL_MINUTES = 60L;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ttlMinutes", TTL_MINUTES);
    }

    @Test
    void requestReset_savesPasswordResetToken_andSendsEmail_whenUserExists() {
        String email = "alice@example.com";
        User user = newUser(email);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(Token.class))).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset(email);

        ArgumentCaptor<Token> captor = ArgumentCaptor.forClass(Token.class);
        verify(tokenRepository).save(captor.capture());
        Token saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getType()).isEqualTo(TokenType.PASSWORD_RESET);
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getExpiresAt())
                .isAfter(Instant.now().plus(TTL_MINUTES - 1, ChronoUnit.MINUTES))
                .isBefore(Instant.now().plus(TTL_MINUTES + 1, ChronoUnit.MINUTES));
        verify(emailService).sendPasswordResetEmail(email, saved.getId());
    }

    @Test
    void requestReset_doesNotLeak_whenEmailUnknown() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        service.requestReset("ghost@example.com");

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void resetPassword_marksTokenUsed_andUpdatesPassword_onHappyPath() {
        String email = "alice@example.com";
        User user = newUser(email);
        user.setPassword("ENCODED_OLD");
        Token token = newValidToken(email, TokenType.PASSWORD_RESET);
        when(tokenRepository.findById(token.getId())).thenReturn(Optional.of(token));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPassword1!")).thenReturn("ENCODED_NEW");

        service.resetPassword(token.getId(), "NewPassword1!");

        assertThat(token.isUsed()).isTrue();
        assertThat(user.getPassword()).isEqualTo("ENCODED_NEW");
        verify(tokenRepository).save(token);
        verify(userRepository).save(user);
    }

    @Test
    void resetPassword_throwsExpired_whenTokenIsExpired() {
        Token expired = newValidToken("alice@example.com", TokenType.PASSWORD_RESET);
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(tokenRepository.findById(expired.getId())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.resetPassword(expired.getId(), "NewPassword1!"))
                .isInstanceOf(ExpiredTokenException.class);

        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void resetPassword_throwsInvalid_whenTokenAlreadyUsed() {
        Token used = newValidToken("alice@example.com", TokenType.PASSWORD_RESET);
        used.setUsed(true);
        when(tokenRepository.findById(used.getId())).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.resetPassword(used.getId(), "NewPassword1!"))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void resetPassword_throwsInvalid_whenTokenIsWrongType() {
        Token wrongType = newValidToken("alice@example.com", TokenType.EMAIL_VERIFICATION);
        when(tokenRepository.findById(wrongType.getId())).thenReturn(Optional.of(wrongType));

        assertThatThrownBy(() -> service.resetPassword(wrongType.getId(), "NewPassword1!"))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void resetPassword_throwsInvalid_whenTokenNotFound() {
        UUID id = UUID.randomUUID();
        when(tokenRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(id, "NewPassword1!"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void resetPassword_throwsInvalid_whenTokenReferencesUnknownUser() {
        Token token = newValidToken("ghost@example.com", TokenType.PASSWORD_RESET);
        when(tokenRepository.findById(token.getId())).thenReturn(Optional.of(token));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(token.getId(), "NewPassword1!"))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
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

    private User newUser(String email) {
        User user = new User();
        user.setId(7L);
        user.setEmail(email);
        user.setEnabled(true);
        user.setVerified(true);
        return user;
    }
}
