package com.checkon.account.infrastructure.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 수명과 쿠키·Origin 정책을 코드 밖 환경설정으로 관리한다.
 *
 * <p>운영 비밀값은 이 클래스나 기본 설정에 포함하지 않고 환경변수로 주입한다.</p>
 */
@ConfigurationProperties("checkon.auth")
public record AuthenticationProperties(
	String jwtSecret,
	Duration accessTokenTtl,
	Duration refreshTokenTtl,
	String refreshCookieName,
	boolean refreshCookieSecure,
	String refreshCookieSameSite,
	List<String> allowedOrigins
) {

	public AuthenticationProperties {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			throw new IllegalArgumentException("checkon.auth.jwt-secret is required");
		}
		accessTokenTtl = requirePositive(accessTokenTtl, "access-token-ttl");
		refreshTokenTtl = requirePositive(refreshTokenTtl, "refresh-token-ttl");
		if (refreshCookieName == null || refreshCookieName.isBlank()) {
			throw new IllegalArgumentException("refresh-cookie-name is required");
		}
		if (refreshCookieSameSite == null || refreshCookieSameSite.isBlank()) {
			throw new IllegalArgumentException("refresh-cookie-same-site is required");
		}
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}
}
