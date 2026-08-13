package com.checkon.engagement.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.security.test-authentication.account-id=0198f000-0000-7000-8000-000000000000",
	"checkon.security.test-authentication.teacher-profile-id=0198f000-0000-7000-8000-000000000001"
})
@ActiveProfiles("dev")
@Testcontainers
class EngagementDevelopmentTestAuthenticationIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	@LocalServerPort
	int port;

	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	@DisplayName("Given 개발 테스트 인증이 활성화됐을 때 When 무헤더 요청의 상태가 잘못되면 Then 400 JSON을 반환한다")
	void rejectsInvalidStatusAsJsonWithoutAuthorizationHeader() throws Exception {
		HttpResponse<String> response = get("/api/v1/engagement/alerts?status=INVALID");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
		var body = objectMapper.readTree(response.body());
		assertThat(body.size()).isEqualTo(2);
		assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST");
		assertThat(body.get("message").asText()).isEqualTo("요청 값을 확인해 주세요.");
	}

	@Test
	@DisplayName("Given 개발 테스트 인증이 활성화됐을 때 When 무헤더로 정상 상태를 조회하면 Then 200을 반환한다")
	void acceptsValidStatusWithoutAuthorizationHeader() throws Exception {
		HttpResponse<String> response = get(
			"/api/v1/engagement/alerts?status=PENDING_REVIEW"
		);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
