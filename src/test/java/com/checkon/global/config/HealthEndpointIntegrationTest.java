package com.checkon.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class HealthEndpointIntegrationTest{

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired
	MockMvc mockMvc;

	@Test
	@DisplayName("Given 인증 정보가 없을 때 When readiness를 조회하면 Then UP을 반환한다")
	void returnsUpWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	@DisplayName("Given 인증 정보가 없을 때 When 비공개 Actuator 경로를 조회하면 Then 거절한다")
	void rejectsNonHealthActuatorEndpoint() throws Exception {
		mockMvc.perform(get("/actuator/env"))
			.andExpect(status().isUnauthorized());
	}
}
