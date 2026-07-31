package com.checkon.global.config;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.checkon.account.infrastructure.security.AuthenticatedAccountService;
import com.checkon.account.infrastructure.security.AuthenticationProperties;
import com.checkon.account.infrastructure.security.JwtAuthenticationFilter;
import com.checkon.account.infrastructure.security.RefreshRequestOriginFilter;

/**
 * 인증 API의 공개 범위, JWT 검증 필터, 비밀번호 인코더와 서명 키를 구성한다.
 *
 * <p>필터 체인은 순서가 중요하다. 1번 체인은 가입·로그인·갱신만 공개하고,
 * dev 전용 2번 체인은 {@link DevSecurityConfiguration}이 담당한다. 마지막 3번
 * 체인은 나머지 요청에 DB 상태까지 확인하는 JWT 인증을 요구한다.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationProperties.class)
public class AccountSecurityConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	@Order(1)
	SecurityFilterChain publicAuthenticationSecurityFilterChain(
		HttpSecurity http,
		AuthenticationProperties properties
	)
		throws Exception {
		// 로그인은 아직 인증 수단을 발급받기 전이고 Refresh는 쿠키 자체가
		// 자격 증명이므로 공개 진입점으로 두되, Refresh의 Origin을 별도 검사한다.
		return http
			.securityMatcher(
				"/api/v1/auth/sign-up/**",
				"/api/v1/auth/login",
				"/api/v1/auth/refresh"
			)
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.addFilterBefore(
				new RefreshRequestOriginFilter(properties),
				UsernamePasswordAuthenticationFilter.class
			)
			.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain authenticatedApiSecurityFilterChain(
		HttpSecurity http,
		JwtDecoder jwtDecoder,
		AuthenticatedAccountService authenticatedAccountService
	) throws Exception {
		// 보호 API는 Authorization 헤더만 사용하므로 브라우저 쿠키 기반 CSRF
		// 공격 대상이 아니다. JWT 필터가 서명과 현재 DB 세션을 모두 확인한다.
		return http
			.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
			.csrf(csrf -> csrf.disable())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
				new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
			))
			.addFilterBefore(
				new JwtAuthenticationFilter(jwtDecoder, authenticatedAccountService),
				UsernamePasswordAuthenticationFilter.class
			)
			.build();
	}

	@Bean
	SecretKey jwtSecretKey(AuthenticationProperties properties) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(properties.jwtSecret());
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
				"checkon.auth.jwt-secret must be Base64",
				exception
			);
		}
		if (decoded.length < 32) {
			throw new IllegalArgumentException(
				"checkon.auth.jwt-secret must contain at least 256 bits"
			);
		}
		// 설정 오류를 시작 시점에 실패시켜 약한 키로 토큰을 발급하는 상태를 막는다.
		return new SecretKeySpec(decoded, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return NimbusJwtEncoder.withSecretKey(jwtSecretKey)
			.algorithm(MacAlgorithm.HS256)
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
	}
}
