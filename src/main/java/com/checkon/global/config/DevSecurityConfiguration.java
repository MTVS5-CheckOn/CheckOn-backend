package com.checkon.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Profile("dev")
@Configuration
public class DevSecurityConfiguration {

	@Bean
	@Order(2)
	SecurityFilterChain devApiSecurityFilterChain(HttpSecurity http)
		throws Exception {
		return http
			.securityMatcher("/api/dev/**")
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain remainingApiSecurityFilterChain(HttpSecurity http)
		throws Exception {
		return http
			.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
			.build();
	}
}
