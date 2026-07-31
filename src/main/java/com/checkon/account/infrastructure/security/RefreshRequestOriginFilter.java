package com.checkon.account.infrastructure.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 브라우저가 쿠키를 자동 전송하는 Refresh 요청의 명시적 Origin을 제한한다.
 *
 * <p>Origin이 없는 서버 간 클라이언트는 허용하고, 브라우저가 보낸 Origin은
 * 설정된 목록과 정확히 일치해야 한다.</p>
 */
public class RefreshRequestOriginFilter extends OncePerRequestFilter {

	private final AuthenticationProperties properties;

	public RefreshRequestOriginFilter(AuthenticationProperties properties) {
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"/api/v1/auth/refresh".equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin != null && !properties.allowedOrigins().contains(origin)) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write(
				"{\"code\":\"ORIGIN_NOT_ALLOWED\",\"message\":\"허용되지 않은 요청입니다.\"}"
			);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
