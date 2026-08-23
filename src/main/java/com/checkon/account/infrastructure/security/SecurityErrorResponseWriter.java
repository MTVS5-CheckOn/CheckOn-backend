package com.checkon.account.infrastructure.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/** Spring Security 단계의 인증·인가 실패를 공개 API 오류 형식으로 고정한다. */
public final class SecurityErrorResponseWriter {

	private SecurityErrorResponseWriter() {
	}

	public static void unauthorized(HttpServletResponse response) throws IOException {
		write(
			response,
			HttpServletResponse.SC_UNAUTHORIZED,
			"UNAUTHORIZED",
			"인증이 필요합니다."
		);
	}

	public static void forbidden(HttpServletResponse response) throws IOException {
		write(
			response,
			HttpServletResponse.SC_FORBIDDEN,
			"FORBIDDEN",
			"요청한 작업을 수행할 권한이 없습니다."
		);
	}

	private static void write(
		HttpServletResponse response,
		int status,
		String code,
		String message
	) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(
			"{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"
		);
	}
}
