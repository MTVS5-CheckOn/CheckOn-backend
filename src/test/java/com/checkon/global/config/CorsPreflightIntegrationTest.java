package com.checkon.global.config;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CorsPreflightIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";
	private static final String DISALLOWED_ORIGIN = "https://attacker.example";

	@Autowired MockMvc mvc;

	@Test
	void allowsProtectedApiPreflightWithoutAuthentication() throws Exception {
		mvc.perform(options("/api/v1/dashboard/briefing")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
				containsStringIgnoringCase("authorization")
			))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
	}

	@Test
	void allowsContentTypeAndIdempotencyKeyHeaders() throws Exception {
		mvc.perform(options("/api/v1/counsel/drafts")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
				.header(
					HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
					"authorization, content-type, idempotency-key"
				))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
				containsStringIgnoringCase("content-type")
			))
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
				containsStringIgnoringCase("idempotency-key")
			));
	}

	@Test
	void rejectsPreflightFromUnlistedOrigin() throws Exception {
		mvc.perform(options("/api/v1/dashboard/briefing")
				.header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void rejectsActualRequestFromUnlistedOrigin() throws Exception {
		mvc.perform(post("/api/v1/auth/login")
				.header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"absent@example.com\",\"password\":\"wrong-password\"}"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void addsCredentialAndExposedHeadersToAllowedActualResponse() throws Exception {
		mvc.perform(post("/api/v1/auth/login")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"absent@example.com\",\"password\":\"wrong-password\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
				containsStringIgnoringCase("location")
			));
	}
}
