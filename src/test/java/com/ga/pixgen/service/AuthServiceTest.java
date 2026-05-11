package com.ga.pixgen.service;

import com.ga.pixgen.dto.ChangePasswordRequest;
import com.ga.pixgen.dto.RegisterRequest;
import com.ga.pixgen.exception.EmailAlreadyExistsException;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private Role userRole;

    @BeforeEach
    void setUp() {
        userRole = new Role();
        userRole.setId(1L);
        userRole.setName("USER");
    }

    @Test
    void register_createsUser_withEncodedPasswordAndDefaultUserRole() {
        RegisterRequest request = new RegisterRequest("alice@example.com", "Password1!", "alice");
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("Password1!")).thenReturn("ENCODED");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = authService.register(request);

        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getPassword()).isEqualTo("ENCODED");
        assertThat(saved.getRole()).isSameAs(userRole);
        assertThat(saved.isVerified()).isFalse();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCredits()).isEqualTo(50);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_throws_whenEmailAlreadyInUse() {
        RegisterRequest request = new RegisterRequest("taken@example.com", "Password1!", "taken");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void findByEmail_delegatesToRepository() {
        User user = new User();
        user.setEmail("bob@example.com");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));

        Optional<User> result = authService.findByEmail("bob@example.com");

        assertThat(result).containsSame(user);
    }

    @Test
    void changePassword_updates_whenCurrentPasswordMatches() {
        User user = new User();
        user.setId(7L);
        user.setEmail("c@example.com");
        user.setPassword("ENCODED_OLD");
        ChangePasswordRequest request = new ChangePasswordRequest("currentPw", "NewPassword1!");
        when(passwordEncoder.matches("currentPw", "ENCODED_OLD")).thenReturn(true);
        when(passwordEncoder.encode("NewPassword1!")).thenReturn("ENCODED_NEW");

        authService.changePassword(user, request);

        assertThat(user.getPassword()).isEqualTo("ENCODED_NEW");
        verify(userRepository).save(user);
    }

    @Test
    void changePassword_throws_whenCurrentPasswordDoesNotMatch() {
        User user = new User();
        user.setPassword("ENCODED_OLD");
        ChangePasswordRequest request = new ChangePasswordRequest("wrongPw", "NewPassword1!");
        when(passwordEncoder.matches("wrongPw", "ENCODED_OLD")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(user, request))
                .isInstanceOf(BadCredentialsException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }
}
