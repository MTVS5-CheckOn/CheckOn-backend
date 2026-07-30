package com.checkon.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 회원가입에 필요한 암호화 도구와 공개 API 보안 경계를 설정한다.
 */
@Configuration(proxyBeanMethods = false)
public class AccountSecurityConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	@Order(1)
	SecurityFilterChain publicSignUpSecurityFilterChain(HttpSecurity http)
		throws Exception {
		// 회원가입 요청만 별도 체인으로 제한해 공개한다.
		// 다른 API는 이후 보안 체인의 인증 정책을 그대로 적용받는다.
		return http
			.securityMatcher("/api/v1/auth/sign-up/**")
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.build();
	}
}
