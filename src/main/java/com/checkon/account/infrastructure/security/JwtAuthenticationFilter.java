package com.checkon.account.infrastructure.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Bearer JWT를 검증하고 DB에서 확인한 AuthenticatedAccount를 SecurityContext에 넣는다.
 *
 * <p>JWT 서명만 유효해도 로그아웃·정지된 계정일 수 있다. 따라서 claim을 그대로
 * 신뢰하지 않고 {@link AuthenticatedAccountService}에서 현재 세션과 Account 상태를
 * 다시 확인한 결과만 Controller에 전달한다.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtDecoder jwtDecoder;
	private final AuthenticatedAccountService authenticatedAccountService;

	public JwtAuthenticationFilter(
		JwtDecoder jwtDecoder,
		AuthenticatedAccountService authenticatedAccountService
	) {
		this.jwtDecoder = jwtDecoder;
		this.authenticatedAccountService = authenticatedAccountService;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}
		try {
			String rawAccessToken = authorization.substring(7);
			Jwt jwt = jwtDecoder.decode(rawAccessToken);
			SecurityContextHolder.getContext().setAuthentication(
				authenticatedAccountService.authenticate(jwt)
			);
			filterChain.doFilter(request, response);
		}
		catch (JwtException exception) {
			SecurityContextHolder.clearContext();
			writeUnauthorized(response);
		}
		catch (AuthenticationException exception) {
			SecurityContextHolder.clearContext();
			writeUnauthorized(response);
		}
	}

	static void writeUnauthorized(HttpServletResponse response) throws IOException {
		SecurityErrorResponseWriter.unauthorized(response);
	}
}
