package com.cic.motor_quote_service.security;

import com.cic.motor_quote_service.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads CIC users from PostgreSQL for Spring Security.
 *
 * Called by JwtAuthenticationFilter on every authenticated request.
 * AppUser implements UserDetails directly, so no adapter class is needed.
 *
 * PERFORMANCE NOTE:
 *   This is called on EVERY request that has a valid JWT token.
 *   Consider adding a Redis cache here in Phase 3.3 once Redis is set up:
 *
 *     @Cacheable(value = "users", key = "#username")
 *     public UserDetails loadUserByUsername(String username) { ... }
 *
 *   TTL should be short (e.g. 5 minutes) so account lockouts propagate quickly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AppUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("UserDetailsService: user not found — username={}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });
    }
}
