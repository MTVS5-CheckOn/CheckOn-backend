package com.checkon.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
	"checkon.openapi.enabled=false",
	"springdoc.api-docs.enabled=false",
	"springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class OpenApiExposureDisabledIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	@Autowired MockMvc mvc;

	@Test
	@DisplayName("Given OpenAPI가 비활성일 때 When 문서 경로를 조회하면 Then 계약과 UI를 노출하지 않는다")
	void hidesSwaggerAndStaticContractWhenDisabled() throws Exception {
		mvc.perform(get("/openapi/dashboard-api.yaml"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/swagger-ui.html"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isNotFound());
	}
}
