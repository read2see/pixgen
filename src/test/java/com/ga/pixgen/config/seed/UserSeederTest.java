package com.ga.pixgen.config.seed;

import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSeederTest {

    private static final String ADMIN_EMAIL = "admin@pixgen.local";
    private static final String ADMIN_PASSWORD = "Admin@12345";
    private static final String ENCODED_PASSWORD = "{bcrypt}encoded-admin-pw";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserSeeder seeder;

    private Map<String, User> userStore;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        userStore = new HashMap<>();
        adminRole = new Role();
        adminRole.setId(1L);
        adminRole.setName("ADMIN");

        ReflectionTestUtils.setField(seeder, "adminEmail", ADMIN_EMAIL);
        ReflectionTestUtils.setField(seeder, "adminPassword", ADMIN_PASSWORD);

        when(userRepository.findByEmail(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(userStore.get(inv.<String>getArgument(0))));
        when(userRepository.save(any(User.class)))
                .thenAnswer(inv -> {
                    User u = inv.getArgument(0);
                    if (u.getId() == null) {
                        u.setId((long) (userStore.size() + 1));
                    }
                    userStore.put(u.getEmail(), u);
                    return u;
                });

        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode(ADMIN_PASSWORD)).thenReturn(ENCODED_PASSWORD);
    }

    @Test
    void seeder_isCommandLineRunner_andOrderedAfterRolePermissionSeeder() {
        assertThat(seeder).isInstanceOf(CommandLineRunner.class);
        assertThat(seeder).isInstanceOf(Ordered.class);
        assertThat(((Ordered) seeder).getOrder()).isEqualTo(2);
    }

    @Test
    void run_createsAdminUser_withEncodedPasswordAndAdminRole() throws Exception {
        seeder.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(saved.getPassword()).isEqualTo(ENCODED_PASSWORD);
        assertThat(saved.getPassword()).isNotEqualTo(ADMIN_PASSWORD);
        assertThat(saved.getRole()).isNotNull();
        assertThat(saved.getRole().getName()).isEqualTo("ADMIN");
    }

    @Test
    void run_marksAdminUser_asVerifiedAndEnabled() throws Exception {
        seeder.run();

        User saved = userStore.get(ADMIN_EMAIL);
        assertThat(saved).isNotNull();
        assertThat(saved.isVerified()).isTrue();
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void run_isIdempotent_acrossMultipleInvocations() throws Exception {
        seeder.run();
        seeder.run();
        seeder.run();

        assertThat(userStore).hasSize(1);
        assertThat(userStore).containsKey(ADMIN_EMAIL);
        verify(userRepository, times(1)).save(any(User.class));
        verify(passwordEncoder, times(1)).encode(ADMIN_PASSWORD);
    }

    @Test
    void run_failsLoudly_whenAdminRoleMissing() {
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seeder.run())
                .as("missing ADMIN role must surface as a startup failure, not silent skip")
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any(User.class));
    }
}
