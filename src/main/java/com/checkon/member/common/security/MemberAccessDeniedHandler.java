package com.checkon.member.common.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberErrorResponse;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * member 체인의 403. 학생 앱이 학부모 경로를 부르는 등 역할 불일치에서 난다.
 *
 * <p>대기 학생 판정({@code STUDENT_ACTIVATION_REQUIRED})은 여기가 아니라 PR3 의
 * StudentActivationGuard 가 한다 — 활성화 테이블이 아직 없다.</p>
 */
public class MemberAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public MemberAccessDeniedHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException
	) throws IOException {
		MemberErrorCode code = MemberErrorCode.ROLE_FORBIDDEN;
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(
			response.getOutputStream(),
			MemberErrorResponse.of(code, "role is not allowed for this path", null)
		);
	}
}
