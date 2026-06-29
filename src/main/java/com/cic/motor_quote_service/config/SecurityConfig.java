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
 * PHASE 2 UPDATE:
 *   - Anonymous users can create and view motor quotes.
 *   - Only authenticated users can initiate payments (ownership enforced in PaymentService).
 *   - M-Pesa callback remains public (called by Safaricom, not our client).
 *
 * PUBLIC ENDPOINTS (no JWT required):
 *   POST /auth/register, /auth/login, /auth/refresh
 *   GET  /actuator/health, /actuator/info
 *   POST /api/v1/motor-quotes                    — anonymous quote creation
 *   GET  /api/v1/motor-quotes/**                 — anonymous quote lookup
 *   POST /api/v1/payments/mpesa/callback           — M-Pesa server-to-server callback
 *
 * PROTECTED ENDPOINTS (JWT required):
 *   POST /api/v1/payments/**                       — payment initiation (ownership checked)
 *   All other /api/**                              — default authenticated
 *
 * ROLE-BASED:
 *   DELETE on any /api/** → ROLE_ADMIN only
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // Public auth endpoints
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()

                        // Kubernetes health probes
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // PHASE 2: Anonymous quote access
                        .requestMatchers(HttpMethod.POST, "/api/v1/motor-quotes").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/motor-quotes/**").permitAll()

                        // M-Pesa callback — called by Safaricom, must be public
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/mpesa/callback").permitAll()

                        // Role-based: only ADMIN can DELETE anything
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}