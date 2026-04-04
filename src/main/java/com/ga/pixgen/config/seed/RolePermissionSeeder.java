package com.ga.pixgen.config.seed;

import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.repository.PermissionRepository;
import com.ga.pixgen.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the canonical Role + Permission catalogue. Runs before any user
 * seeder (order = 1) so user seeders can resolve roles by name.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RolePermissionSeeder implements CommandLineRunner, Ordered {

    static final Set<String> ALL_PERMISSIONS = new LinkedHashSet<>(Set.of(
            "user.read", "user.update", "user.delete",
            "image.create", "image.read",
            "post.create", "post.read", "post.delete",
            "comment.create", "comment.delete",
            "role.manage", "permission.manage",
            "job.create", "job.read", "credits.grant"
    ));

    static final Set<String> ADMIN_PERMISSIONS = ALL_PERMISSIONS;

    static final Set<String> MODERATOR_PERMISSIONS = Set.of(
            "user.read", "image.read", "post.read", "job.read",
            "post.delete", "comment.delete"
    );

    static final Set<String> USER_PERMISSIONS = Set.of(
            "image.create", "image.read",
            "post.create", "post.read",
            "comment.create",
            "job.create", "job.read"
    );

    static final Set<String> SYSTEM_PERMISSIONS = Set.of(
            "job.create", "job.read", "credits.grant"
    );

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, Permission> permissions = ensurePermissions();
        ensureRole("ADMIN", ADMIN_PERMISSIONS, permissions);
        ensureRole("MODERATOR", MODERATOR_PERMISSIONS, permissions);
        ensureRole("USER", USER_PERMISSIONS, permissions);
        ensureRole("SYSTEM", SYSTEM_PERMISSIONS, permissions);
    }

    private Map<String, Permission> ensurePermissions() {
        Map<String, Permission> result = new LinkedHashMap<>();
        for (String name : ALL_PERMISSIONS) {
            Permission permission = permissionRepository.findByPermission(name)
                    .orElseGet(() -> {
                        Permission p = new Permission();
                        p.setPermission(name);
                        return permissionRepository.save(p);
                    });
            result.put(name, permission);
        }
        return result;
    }

    private void ensureRole(String name, Set<String> permissionNames, Map<String, Permission> permissions) {
        if (roleRepository.findByName(name).isPresent()) {
            return;
        }
        Set<Permission> wired = new HashSet<>();
        for (String permissionName : permissionNames) {
            Permission permission = permissions.get(permissionName);
            if (permission == null) {
                throw new IllegalStateException(
                        "Cannot wire role '" + name + "': missing permission '" + permissionName + "'");
            }
            wired.add(permission);
        }
        Role role = new Role();
        role.setName(name);
        role.setPermissions(wired);
        roleRepository.save(role);
    }
}
