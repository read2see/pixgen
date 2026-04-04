package com.ga.pixgen.config.seed;

import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.repository.PermissionRepository;
import com.ga.pixgen.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RolePermissionSeederTest {

    private static final Set<String> EXPECTED_PERMISSIONS = Set.of(
            "user.read", "user.update", "user.delete",
            "image.create", "image.read",
            "post.create", "post.read", "post.delete",
            "comment.create", "comment.delete",
            "role.manage", "permission.manage",
            "job.create", "job.read", "credits.grant"
    );

    private static final Set<String> EXPECTED_ADMIN = EXPECTED_PERMISSIONS;

    private static final Set<String> EXPECTED_MODERATOR = Set.of(
            "user.read", "image.read", "post.read", "job.read",
            "post.delete", "comment.delete"
    );

    private static final Set<String> EXPECTED_USER = Set.of(
            "image.create", "image.read",
            "post.create", "post.read",
            "comment.create",
            "job.create", "job.read"
    );

    private static final Set<String> EXPECTED_SYSTEM = Set.of(
            "job.create", "job.read", "credits.grant"
    );

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private RolePermissionSeeder seeder;

    private Map<String, Permission> permissionStore;
    private Map<String, Role> roleStore;

    @BeforeEach
    void setUp() {
        permissionStore = new HashMap<>();
        roleStore = new HashMap<>();

        when(permissionRepository.findByPermission(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(permissionStore.get(inv.<String>getArgument(0))));
        when(permissionRepository.save(any(Permission.class)))
                .thenAnswer(inv -> {
                    Permission p = inv.getArgument(0);
                    if (p.getId() == null) {
                        p.setId((long) (permissionStore.size() + 1));
                    }
                    permissionStore.put(p.getPermission(), p);
                    return p;
                });

        when(roleRepository.findByName(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(roleStore.get(inv.<String>getArgument(0))));
        when(roleRepository.save(any(Role.class)))
                .thenAnswer(inv -> {
                    Role r = inv.getArgument(0);
                    if (r.getId() == null) {
                        r.setId((long) (roleStore.size() + 1));
                    }
                    roleStore.put(r.getName(), r);
                    return r;
                });
    }

    @Test
    void seeder_isCommandLineRunner_andOrderedFirst() {
        assertThat(seeder).isInstanceOf(CommandLineRunner.class);
        assertThat(seeder).isInstanceOf(Ordered.class);
        assertThat(((Ordered) seeder).getOrder()).isEqualTo(1);
    }

    @Test
    void run_seedsAllPermissions() throws Exception {
        seeder.run();

        assertThat(permissionStore.keySet())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PERMISSIONS);
    }

    @Test
    void run_seedsAllFourRoles() throws Exception {
        seeder.run();

        assertThat(roleStore.keySet())
                .containsExactlyInAnyOrder("ADMIN", "MODERATOR", "USER", "SYSTEM");
    }

    @Test
    void run_wiresAdminRole_withAllPermissions() throws Exception {
        seeder.run();

        assertThat(permissionNames(roleStore.get("ADMIN")))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ADMIN);
    }

    @Test
    void run_wiresModeratorRole_withReadAndDeletePermissions() throws Exception {
        seeder.run();

        assertThat(permissionNames(roleStore.get("MODERATOR")))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MODERATOR);
    }

    @Test
    void run_wiresUserRole_withCoreUserPermissions() throws Exception {
        seeder.run();

        assertThat(permissionNames(roleStore.get("USER")))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_USER);
    }

    @Test
    void run_wiresSystemRole_withJobAndCreditPermissions() throws Exception {
        seeder.run();

        assertThat(permissionNames(roleStore.get("SYSTEM")))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SYSTEM);
    }

    @Test
    void run_isIdempotent_acrossMultipleInvocations() throws Exception {
        seeder.run();
        seeder.run();

        assertThat(permissionStore.keySet())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PERMISSIONS);
        assertThat(roleStore.keySet())
                .containsExactlyInAnyOrder("ADMIN", "MODERATOR", "USER", "SYSTEM");
        verify(permissionRepository, times(EXPECTED_PERMISSIONS.size())).save(any(Permission.class));
        verify(roleRepository, times(4)).save(any(Role.class));
    }

    private static Set<String> permissionNames(Role role) {
        if (role == null || role.getPermissions() == null) {
            return Set.of();
        }
        return role.getPermissions().stream()
                .map(Permission::getPermission)
                .collect(Collectors.toSet());
    }
}
