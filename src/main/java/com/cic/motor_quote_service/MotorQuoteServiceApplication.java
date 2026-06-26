package com.cic.motor_quote_service;

import com.cic.motor_quote_service.entity.AppUser;
import com.cic.motor_quote_service.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
@SpringBootApplication
public class MotorQuoteServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MotorQuoteServiceApplication.class, args);
	}

	// ← ADD THIS — remove after first run
	@Bean
	@Profile("default")
	CommandLineRunner seedUsers(AppUserRepository userRepository,
	                            PasswordEncoder passwordEncoder) {
		return args -> {
			if (userRepository.findByUsername("admin").isEmpty()) {
				userRepository.save(AppUser.builder()
						.username("admin")
						.passwordHash(passwordEncoder.encode("Admin@CIC2026"))
						.email("admin@cic.co.ke")
						.fullName("CIC System Admin")
						.role(AppUser.Role.ROLE_ADMIN)
						.build());
				System.out.println(">>> SEEDED: admin");
			}

			if (userRepository.findByUsername("agent01").isEmpty()) {
				userRepository.save(AppUser.builder()
						.username("agent01")
						.passwordHash(passwordEncoder.encode("Admin@CIC2026"))
						.email("agent01@cic.co.ke")
						.fullName("Test Agent One")
						.role(AppUser.Role.ROLE_AGENT)
						.build());
				System.out.println(">>> SEEDED: agent01");
			}

			if (userRepository.findByUsername("viewer01").isEmpty()) {
				userRepository.save(AppUser.builder()
						.username("viewer01")
						.passwordHash(passwordEncoder.encode("Admin@CIC2026"))
						.email("viewer01@cic.co.ke")
						.fullName("Test Viewer One")
						.role(AppUser.Role.ROLE_USER)
						.build());
				System.out.println(">>> SEEDED: viewer01");
			}
		};
	}
}
