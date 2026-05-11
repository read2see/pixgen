package com.ga.pixgen.security;

import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService service;

    @Test
    void loadUserByUsername_returnsDetails_withRoleAndPermissionAuthorities() {
        Permission readUsers = permission(1L, "user.read");
        Permission createPosts = permission(2L, "post.create");
        Role role = new Role();
        role.setId(1L);
        role.setName("USER");
        Set<Permission> permissions = new HashSet<>();
        permissions.add(readUsers);
        permissions.add(createPosts);
        role.setPermissions(permissions);

        User user = new User();
        user.setId(42L);
        user.setEmail("carol@example.com");
        user.setPassword("ENC");
        user.setEnabled(true);
        user.setVerified(true);
        user.setRole(role);

        when(userRepository.findByEmailAndDeletedAtIsNull("carol@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("carol@example.com");

        assertThat(details.getUsername()).isEqualTo("carol@example.com");
        assertThat(details.getPassword()).isEqualTo("ENC");
        assertThat(details.isEnabled()).isTrue();

        Set<String> authorityNames = details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertThat(authorityNames).containsExactlyInAnyOrder(
                "ROLE_USER", "user.read", "post.create");
    }

    @Test
    void loadUserByUsername_throwsUsernameNotFound_whenUserMissing() {
        when(userRepository.findByEmailAndDeletedAtIsNull("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private Permission permission(long id, String name) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermission(name);
        return p;
    }
}
