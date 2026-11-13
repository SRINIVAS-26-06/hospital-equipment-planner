package com.hospital.planner.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Backend authorization (the UI only mirrors this). HTTP Basic against persistent users, stateless.
 * Ownership rules (cancel own booking, assigned engineer, ...) are enforced in the services.
 *
 *  ADMIN     everything
 *  ENGINEER  read; schedule/run maintenance + calibration; work orders; documents; report breakdowns
 *  STAFF     read; bookings (create, cancel own); usage; report breakdowns
 *  VIEWER    read equipment, availability, maintenance, calibration, analytics, dashboard
 */
@Configuration
public class SecurityConfig {
    private static void json(HttpServletResponse res, int status, String code, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * 401 carries NO "WWW-Authenticate" challenge. A Basic challenge makes browsers pop up a login box and then cache and
     * automatically resend the credentials, which is what would make CSRF possible. The React app sends the Authorization
     * header itself, so no challenge is needed, and CSRF protection can stay off for this stateless, cookie-less API.
     */
    private static final AuthenticationEntryPoint ENTRY_POINT =
            (req, res, ex) -> json(res, 401, "UNAUTHORIZED", "Authentication is required or the credentials were not accepted");
    private static final AccessDeniedHandler DENIED =
            (req, res, ex) -> json(res, 403, "FORBIDDEN", "You do not have permission for this action");

    private static final String[] ALL = {"VIEWER", "STAFF", "ENGINEER", "ADMIN"};
    private static final String[] OPERATIONAL = {"STAFF", "ENGINEER", "ADMIN"};

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)   // stateless API with HTTP Basic, no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                // administration
                .requestMatchers("/api/users/**", "/api/audit/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/notifications/scan").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/departments/**", "/api/assets/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/departments/**", "/api/assets/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/departments/**", "/api/assets/**").hasRole("ADMIN")

                // reads available to every signed-in role
                .requestMatchers(HttpMethod.GET, "/api/me", "/api/dashboard", "/api/analytics/**", "/api/assets/**",
                        "/api/departments/**", "/api/maintenance/**", "/api/calibrations/**", "/api/documents/**", "/api/downtime/**")
                    .hasAnyRole(ALL)
                .requestMatchers("/api/notifications/**").hasAnyRole(ALL)

                // operational data is not shown to read-only viewers
                .requestMatchers(HttpMethod.GET, "/api/bookings/**", "/api/usage/**", "/api/work-orders/**")
                    .hasAnyRole(OPERATIONAL)
                .requestMatchers(HttpMethod.POST, "/api/bookings/**", "/api/usage/**").hasAnyRole("STAFF", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/work-orders/*/triage").hasAnyRole("ENGINEER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/work-orders/**").hasAnyRole(OPERATIONAL)

                // engineering work
                .requestMatchers(HttpMethod.POST, "/api/documents/**").hasAnyRole(OPERATIONAL)   // ownership checked in DocumentService
                .requestMatchers(HttpMethod.POST, "/api/maintenance/**", "/api/calibrations/**", "/api/downtime/**")
                    .hasAnyRole("ENGINEER", "ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/maintenance/**", "/api/calibrations/**")
                    .hasAnyRole("ENGINEER", "ADMIN")

                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(ENTRY_POINT).accessDeniedHandler(DENIED))
            .httpBasic(b -> b.authenticationEntryPoint(ENTRY_POINT));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
