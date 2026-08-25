package com.checkon.member.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.member.MemberPackageMarker;

/**
 * {@code @RestControllerAdvice} 의 범위가 member 로만 묶여 있는지 본다.
 *
 * <p>범위를 두지 않으면 승우님 컨트롤러의 top-level {@code {code, message}} 응답이
 * member 봉투 {@code {error:{...}}} 로 바뀐다. 프론트의 기존 분기가 전부 깨진다.</p>
 *
 * <p>🔴 리터럴 {@code "AUTHENTICATION_REQUIRED"} 대신 기존 코드 문자열을 쓴다 — 이 테스트가
 * 지켜야 하는 것은 enum 이름이 아니라 <b>와이어 계약</b>이다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class MemberExceptionHandlerScopeTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@Autowired MockMvc mockMvc;

	@Test
	@DisplayName("🔴 advice 범위가 member 패키지로 선언돼 있다")
	void handlerIsScopedToMemberPackage() {
		// 🔴 행위만으로는 이 규칙을 지킬 수 없다. 기존 패키지는 저마다 더 구체적인
		//    @ExceptionHandler 를 갖고 있어서, 범위를 풀어도 당장은 응답이 바뀌지 않는다.
		//    하지만 구체 핸들러가 없는 엔드포인트가 새로 생기면 그때부터 member 봉투를 뒤집어쓴다.
		//    그래서 선언 자체를 단언한다.
		RestControllerAdvice advice =
			MemberExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

		assertThat(advice).isNotNull();
		assertThat(advice.basePackageClasses())
			.as("범위를 풀면 승우님 컨트롤러의 오류 형식을 삼킬 수 있다")
			.contains(MemberPackageMarker.class);
	}

	@Test
	@DisplayName("기존 강사 API 의 오류는 top-level {code, message} 를 유지한다")
	void scopeIsMemberOnly() throws Exception {
		mockMvc.perform(get("/api/v1/dashboard/briefing"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
			.andExpect(jsonPath("$.message").exists())
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	@DisplayName("기존 인증 API 의 401 도 member 봉투로 바뀌지 않는다")
	void existingAuthenticationErrorKeepsItsShape() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").exists())
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	@DisplayName("member 경로의 오류는 member 봉투를 쓴다")
	void memberPathUsesMemberEnvelope() throws Exception {
		mockMvc.perform(get("/api/v1/member/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
			.andExpect(jsonPath("$.code").doesNotExist());
	}
}
