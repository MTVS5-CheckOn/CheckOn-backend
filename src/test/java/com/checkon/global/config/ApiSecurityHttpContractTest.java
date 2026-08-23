package com.checkon.global.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiSecurityHttpContractTest {

	private static final String FRONTEND_ORIGIN = "http://localhost:3000";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	@Autowired MockMvc mvc;

	@Test
	@DisplayName("Given 인증이 없을 때 When 운영 API를 호출하면 Then CORS와 JSON 401 계약을 반환한다")
	void returnsStableJsonForMissingAuthentication() throws Exception {
		mvc.perform(get("/api/v1/dashboard/briefing")
				.header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
				.param("date", "2026-08-23"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.message").value("인증이 필요합니다."))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	@DisplayName("Given PARENT 권한일 때 When 상담 API를 호출하면 Then Controller 전에 JSON 403으로 거절한다")
	void requiresTeacherRoleForCounselApi() throws Exception {
		mvc.perform(get("/api/v1/counsel/drafts/job-1")
				.with(user("parent").roles("PARENT"))
				.header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.message")
				.value("요청한 작업을 수행할 권한이 없습니다."))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN));
	}
}
