package com.checkon.member.integration.account;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.checkon.account.infrastructure.security.AuthenticatedAccountService;
import com.checkon.account.infrastructure.security.JwtAuthenticationFilter;

/**
 * member 체인이 쓸 JWT 필터를 만든다.
 *
 * <p>🔴 {@code JwtAuthenticationFilter} 는 빈이 아니다 — 기존 설정도 인라인으로 생성한다.
 * 여기서도 빈으로 노출하지 않는 것이 중요하다. Filter 타입 빈은 서블릿 컨테이너에 자동 등록되어
 * 모든 경로에 걸리므로, 노출하는 순간 승우님 엔드포인트의 동작이 바뀐다.</p>
 *
 * <p>필터는 상태를 갖지 않으므로 인스턴스가 둘이어도 무방하다.</p>
 */
@Component
public class MemberJwtFilterFactory {

	private final JwtDecoder jwtDecoder;
	private final AuthenticatedAccountService authenticatedAccountService;

	public MemberJwtFilterFactory(
		JwtDecoder jwtDecoder,
		AuthenticatedAccountService authenticatedAccountService
	) {
		this.jwtDecoder = jwtDecoder;
		this.authenticatedAccountService = authenticatedAccountService;
	}

	public OncePerRequestFilter create() {
		return new JwtAuthenticationFilter(jwtDecoder, authenticatedAccountService);
	}
}
