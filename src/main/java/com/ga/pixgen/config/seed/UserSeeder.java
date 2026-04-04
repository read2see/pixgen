package com.ga.pixgen.config.seed;

import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.RoleRepository;
import com.ga.pixgen.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the bootstrap admin user. Runs after {@link RolePermissionSeeder}
 * (order = 2) so the canonical {@code ADMIN} role is guaranteed to exist.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class UserSeeder implements CommandLineRunner, Ordered {

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.admin.email:admin@pixgen.local}")
    private String adminEmail;

    @Value("${seed.admin.password:Admin@12345}")
    private String adminPassword;

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.findByEmail(adminEmail).isPresent()) {
            return;
        }

        Role adminRole = roleRepository.findByName(ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot seed admin user: role '" + ADMIN_ROLE
                                + "' is missing. Ensure RolePermissionSeeder ran first."));

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRole(adminRole);
        admin.setVerified(true);
        admin.setEnabled(true);

        userRepository.save(admin);
    }
}
