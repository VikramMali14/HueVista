package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.util.Emails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a single ROLE_ADMIN on startup from {@code app.admin.email} / {@code app.admin.password}
 * (bound to the ADMIN_EMAIL / ADMIN_PASSWORD env vars). No-op unless BOTH are set, and never
 * overwrites an existing account — so the admin password is never committed to source.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            return; // not configured — skip
        }
        String email = Emails.normalize(adminEmail);
        if (userRepository.existsByEmail(email)) {
            log.info("Admin user already present, not reseeding: {}", email);
            return;
        }
        User admin = User.builder()
                .name("Administrator")
                .email(email)
                .password(passwordEncoder.encode(adminPassword))
                .provider(AuthProvider.LOCAL)
                .emailVerified(true)
                .phoneVerified(false)
                .role(UserRole.ADMIN)
                .build();
        userRepository.save(admin);
        log.info("Seeded ROLE_ADMIN user: {}", email);
    }
}
