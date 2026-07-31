package com.checkon.account.infrastructure.security;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.checkon.account.domain.Account;

/**
 * API 인증에 필요한 최소 식별 정보만 담은 짧은 수명의 JWT를 발급한다.
 */
@Component
public class AccessTokenService {

	private final JwtEncoder jwtEncoder;
	private final AuthenticationProperties properties;

	public AccessTokenService(
		JwtEncoder jwtEncoder,
		AuthenticationProperties properties
	) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public IssuedAccessToken issue(
		Account account,
		UUID sessionId,
		Instant issuedAt
	) {
		Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(account.id().toString())
			.claim("role", account.role().name())
			.claim("sid", sessionId.toString())
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString())
			.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(claims))
			.getTokenValue();
		return new IssuedAccessToken(token, expiresAt);
	}

	public record IssuedAccessToken(String value, Instant expiresAt) {
	}
}
