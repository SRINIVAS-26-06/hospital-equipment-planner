package com.hospital.planner.config;

import com.hospital.planner.entity.User;
import com.hospital.planner.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the four initial accounts on first start only (never overwrites existing users).
 * Passwords come from PLANNER_*_PASSWORD (development defaults are unchanged); change them in the Users screen.
 */
@Component
public class BootstrapUsers implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapUsers.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String viewer, staff, engineer, admin;

    public BootstrapUsers(UserRepository users, PasswordEncoder encoder,
                          @Value("${planner.users.viewer-password}") String viewer,
                          @Value("${planner.users.staff-password}") String staff,
                          @Value("${planner.users.engineer-password}") String engineer,
                          @Value("${planner.users.admin-password}") String admin) {
        this.users = users;
        this.encoder = encoder;
        this.viewer = viewer;
        this.staff = staff;
        this.engineer = engineer;
        this.admin = admin;
    }

    @Override
    public void run(ApplicationArguments args) {
        create("admin", "System Administrator", "ADMIN", admin);
        create("engineer", "Biomedical Engineer", "ENGINEER", engineer);
        create("staff", "Clinical Staff", "STAFF", staff);
        create("viewer", "Read-only Viewer", "VIEWER", viewer);
    }

    private void create(String username, String fullName, String role, String password) {
        if (users.existsByUsernameIgnoreCase(username)) return;
        User u = new User();
        u.username = username;
        u.passwordHash = encoder.encode(password);
        u.fullName = fullName;
        u.role = role;
        users.save(u);
        if (password.endsWith("-dev")) log.warn("User '{}' was created with a development password; set PLANNER_{}_PASSWORD", username, role);
    }
}
