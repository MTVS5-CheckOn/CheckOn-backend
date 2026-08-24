package com.checkon.account.infrastructure.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

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
	private static final List<String> SUPPORTED_SAME_SITE = List.of("Strict", "Lax", "None");
	private static final String SAME_SITE_NONE = "None";

	public AuthenticationProperties {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			throw new IllegalArgumentException("checkon.auth.jwt-secret is required");
		}
		accessTokenTtl = requirePositive(accessTokenTtl, "access-token-ttl");
		refreshTokenTtl = requirePositive(refreshTokenTtl, "refresh-token-ttl");
		if (refreshCookieName == null || refreshCookieName.isBlank()) {
			throw new IllegalArgumentException("refresh-cookie-name is required");
		}
		refreshCookieSameSite = requireSupportedSameSite(refreshCookieSameSite);
		if (SAME_SITE_NONE.equals(refreshCookieSameSite) && !refreshCookieSecure) {
			throw new IllegalArgumentException(
				"refresh-cookie-same-site=None requires refresh-cookie-secure=true"
			);
		}
		allowedOrigins = requireValidOrigins(allowedOrigins);
	}

	private static String requireSupportedSameSite(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("refresh-cookie-same-site is required");
		}
		String trimmed = value.trim();
		return SUPPORTED_SAME_SITE.stream()
			.filter(supported -> supported.equalsIgnoreCase(trimmed))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"refresh-cookie-same-site must be one of "
					+ SUPPORTED_SAME_SITE + " but was: " + trimmed
			));
	}

	private static List<String> requireValidOrigins(List<String> values) {
		if (values == null || values.isEmpty()
			|| values.stream().allMatch(value -> value == null || value.isBlank())) {
			return List.of();
		}
		var origins = new LinkedHashSet<String>();
		for (String value : values) {
			String origin = value == null ? "" : value.trim();
			if (origin.isEmpty()) {
				throw invalidOrigin(value, "must not be blank");
			}
			URI uri;
			try {
				uri = new URI(origin);
			}
			catch (URISyntaxException exception) {
				throw invalidOrigin(value, "must be a valid URI", exception);
			}
			String scheme = uri.getScheme() == null
				? ""
				: uri.getScheme().toLowerCase(Locale.ROOT);
			if (!("http".equals(scheme) || "https".equals(scheme))) {
				throw invalidOrigin(value, "scheme must be http or https");
			}
			if (uri.getHost() == null || uri.getUserInfo() != null) {
				throw invalidOrigin(value, "must contain only a host and optional port");
			}
			if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty())
				|| uri.getRawQuery() != null || uri.getRawFragment() != null) {
				throw invalidOrigin(value, "must not contain a path, query, or fragment");
			}
			origins.add(origin);
		}
		return List.copyOf(origins);
	}

	private static IllegalArgumentException invalidOrigin(String value, String reason) {
		return new IllegalArgumentException("invalid allowed origin '" + value + "': " + reason);
	}

	private static IllegalArgumentException invalidOrigin(
		String value,
		String reason,
		Exception cause
	) {
		return new IllegalArgumentException(
			"invalid allowed origin '" + value + "': " + reason,
			cause
		);
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}
}
