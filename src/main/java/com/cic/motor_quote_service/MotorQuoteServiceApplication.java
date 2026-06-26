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

}
