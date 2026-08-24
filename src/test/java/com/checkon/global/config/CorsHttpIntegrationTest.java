package com.checkon.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class CorsHttpIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";
	private static final String DISALLOWED_ORIGIN = "https://attacker.example";

	@LocalServerPort
	int port;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	@DisplayName("Given 허용 Origin일 때 When 보호 API preflight를 보내면 Then 인증 없이 허용한다")
	void allowsProtectedPreflightOverRealHttp() throws Exception {
		HttpRequest request = request("/api/v1/dashboard/briefing")
			.header("Origin", ALLOWED_ORIGIN)
			.header("Access-Control-Request-Method", "GET")
			.header("Access-Control-Request-Headers", "authorization")
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.build();

		HttpResponse<String> response = send(request);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
			.contains(ALLOWED_ORIGIN);
		assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
			.contains("true");
	}

	@Test
	@DisplayName("Given 허용 Origin일 때 When 실제 로그인 요청이 실패해도 Then CORS 응답 헤더를 유지한다")
	void keepsCorsHeadersOnActualErrorResponseOverRealHttp() throws Exception {
		HttpRequest request = request("/api/v1/auth/login")
			.header("Origin", ALLOWED_ORIGIN)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
				"{\"email\":\"absent@example.com\",\"password\":\"wrong-password\"}"
			))
			.build();

		HttpResponse<String> response = send(request);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
			.contains(ALLOWED_ORIGIN);
		assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
			.contains("true");
	}

	@Test
	@DisplayName("Given 비허용 Origin일 때 When 실제 요청을 보내면 Then 애플리케이션 진입 전에 거부한다")
	void rejectsUnlistedOriginOverRealHttp() throws Exception {
		HttpRequest request = request("/api/v1/auth/login")
			.header("Origin", DISALLOWED_ORIGIN)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
				"{\"email\":\"absent@example.com\",\"password\":\"wrong-password\"}"
			))
			.build();

		HttpResponse<String> response = send(request);

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
