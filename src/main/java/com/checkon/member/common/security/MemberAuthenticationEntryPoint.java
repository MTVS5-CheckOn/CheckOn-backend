package com.checkon.member.common.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberErrorResponse;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * member 체인의 401. 승우님 체인의 {@code {code, message}} 형식과 달리 member 봉투를 쓴다.
 */
public class MemberAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public MemberAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authenticationException
	) throws IOException {
		MemberErrorCode code = MemberErrorCode.AUTHENTICATION_REQUIRED;
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(
			response.getOutputStream(),
			MemberErrorResponse.of(code, "authentication is required", null)
		);
	}
}
