package com.checkon.member.common.presentation;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * member 응답에 {@code X-Request-Id} 를 항상 실어 보낸다. 클라이언트가 문의할 때 이 값을 준다.
 *
 * <p>🔴 클라이언트 값을 그대로 신뢰하지 않는다. 길이·문자를 검증하고 어긋나면 서버 생성 값으로
 * 대체한다 — 개행이 섞인 값이 로그에 들어가면 로그 인젝션이 된다.</p>
 *
 * <p>🔴 이 필터는 빈이 아니다. Filter 타입 빈은 모든 경로에 자동 등록되므로, member 보안 체인이
 * 직접 인스턴스를 만들어 자기 체인에만 건다.</p>
 */
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	static final String MDC_KEY = "requestId";
	private static final int MAX_LENGTH = 64;
	private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String requestId = sanitize(request.getHeader(HEADER));
		response.setHeader(HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	static String sanitize(String candidate) {
		if (candidate == null
			|| candidate.isEmpty()
			|| candidate.length() > MAX_LENGTH
			|| !ALLOWED.matcher(candidate).matches()) {
			return UUID.randomUUID().toString();
		}
		return candidate;
	}
}
