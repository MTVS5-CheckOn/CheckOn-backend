package com.checkon.global.config;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 개발 단계에서 인증 헤더가 없는 보호 API 요청에 고정 테스트 강사 인증을 제공한다. */
public class DevelopmentTestAuthenticationFilter extends OncePerRequestFilter {
	private final DevelopmentTestAuthenticationProperties properties;

	public DevelopmentTestAuthenticationFilter(
		DevelopmentTestAuthenticationProperties properties
	) {
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (!properties.enabled()
			|| request.getRequestURI().startsWith("/api/v1/auth/")
			|| request.getHeader(HttpHeaders.AUTHORIZATION) != null
			|| SecurityContextHolder.getContext().getAuthentication() != null) {
			filterChain.doFilter(request, response);
			return;
		}

		AuthenticatedAccount principal = new AuthenticatedAccount(
			properties.accountId(),
			AccountRole.TEACHER,
			properties.teacherProfileId(),
			null
		);
		var authentication = UsernamePasswordAuthenticationToken.authenticated(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
		);
		SecurityContextHolder.getContext().setAuthentication(authentication);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}
}
