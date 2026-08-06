package com.checkon.global.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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

/** 개발 단계의 대시보드 v1 계약 API에만 고정된 테스트 강사 인증을 제공한다. */
public class DashboardTestAuthenticationFilter extends OncePerRequestFilter {
	private static final UUID TEST_ACCOUNT_ID = UUID.nameUUIDFromBytes(
		"checkon-dashboard-test-account".getBytes(StandardCharsets.UTF_8)
	);
	private static final UUID TEST_SESSION_ID = UUID.nameUUIDFromBytes(
		"checkon-dashboard-test-session".getBytes(StandardCharsets.UTF_8)
	);

	private final DashboardTestAuthenticationProperties properties;

	public DashboardTestAuthenticationFilter(
		DashboardTestAuthenticationProperties properties
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
			|| request.getHeader(HttpHeaders.AUTHORIZATION) != null
			|| SecurityContextHolder.getContext().getAuthentication() != null
			|| !isDashboardContractRequest(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		AuthenticatedAccount principal = new AuthenticatedAccount(
			TEST_ACCOUNT_ID,
			AccountRole.TEACHER,
			properties.teacherProfileId(),
			TEST_SESSION_ID
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

	private boolean isDashboardContractRequest(HttpServletRequest request) {
		String method = request.getMethod();
		String path = request.getRequestURI();
		if ("GET".equals(method)) {
			return path.equals("/api/v1/dashboard/briefing")
				|| path.equals("/api/v1/dashboard/calendar")
				|| path.equals("/api/v1/engagement/alerts")
				|| path.matches("/api/v1/engagement/alerts/[^/]+");
		}
		if ("POST".equals(method)) {
			return path.matches("/api/v1/engagement/alerts/[^/]+/(approval|rejection|interventions)")
				|| path.matches("/api/v1/engagement/interventions/[^/]+/(completion|cancellation)")
				|| path.matches("/api/v1/engagement/reminders/[^/]+/(completion|cancellation)");
		}
		if ("PATCH".equals(method)) {
			return path.matches("/api/v1/todos/[^/]+");
		}
		return "PUT".equals(method)
			&& path.matches("/api/v1/students/[^/]+/personal-information/name");
	}
}
