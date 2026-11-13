package com.hospital.planner.config;

import com.hospital.planner.entity.User;
import com.hospital.planner.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Users live in PostgreSQL (app_users) with BCrypt hashes. */
@Service
public class DbUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public DbUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        User u = users.findByUsernameIgnoreCase(username).orElseThrow(() -> new UsernameNotFoundException("unknown user"));
        return org.springframework.security.core.userdetails.User.withUsername(u.username)
                .password(u.passwordHash).roles(u.role).disabled(!u.active).build();
    }
}
