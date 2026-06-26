package com.cic.motor_quote_service.config;

import com.cic.motor_quote_service.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration for CIC Motor Quote Service.
 *
 * KEY DECISIONS:
 *   - CSRF disabled: we are a stateless REST API; CSRF only matters for
 *     browser-session-based apps. Our clients send JWT, not cookies.
 *   - Session policy STATELESS: Spring Security will never create an HttpSession.
 *     Every request must carry a valid JWT — no server-side sessions.
 *   - @EnableMethodSecurity: enables @PreAuthorize at the method level.
 *     Use this to gate individual service methods by role.
 *
 * PUBLIC ENDPOINTS (no JWT required):
 *   POST /auth/login     — get tokens
 *   POST /auth/refresh   — exchange refresh token for new access token
 *   GET  /actuator/health — Kubernetes liveness/readiness probe
 *
 * ROLE-BASED RULES:
 *   DELETE on any /api/** → ROLE_ADMIN only
 *   Everything else authenticated → any authenticated user
 *
 * INTERN NOTE: WebSecurityConfigurerAdapter is GONE in Spring Security 6.
 * The new way is a @Bean SecurityFilterChain. Do not extend anything.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity          // Enables @PreAuthorize, @PostAuthorize, @Secured
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ── CSRF: off (stateless JWT API) ─────────────────────────────────
                .csrf(AbstractHttpConfigurer::disable)

                // ── Session: stateless (no HttpSession) ───────────────────────────
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── Request authorization rules ───────────────────────────────────
                .authorizeHttpRequests(auth -> auth

                        // Public auth endpoints
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()

                        // Kubernetes health probes — must be public
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // Role-based: only ADMIN can DELETE anything
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")

                        // All remaining API calls require authentication
                        // Role granularity is enforced at the @PreAuthorize level
                        .anyRequest().authenticated()
                )

                // ── Auth provider: username/password from DB ──────────────────────
                .authenticationProvider(authenticationProvider())

                // ── JWT filter before Spring's built-in username/password filter ──
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * DaoAuthenticationProvider: wires our UserDetailsService + BCrypt together.
     * Used by AuthenticationManager during POST /auth/login.
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * BCrypt with strength 12 — CIC security standard.
     * Strength 10 is the Spring default; 12 adds ~4x more hashing time
     * without noticeable latency on login (human-facing, happens once).
     *
     * NEVER use MD5, SHA-1, or plain SHA-256 for passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Exposes AuthenticationManager so AuthService can call it for login.
     * Spring Boot 3 does not expose this by default — must be declared explicitly.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
