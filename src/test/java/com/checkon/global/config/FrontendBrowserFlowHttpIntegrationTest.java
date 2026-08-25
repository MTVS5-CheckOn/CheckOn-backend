package com.checkon.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class FrontendBrowserFlowHttpIntegrationTest {

	private static final String FRONTEND_ORIGIN = "http://localhost:3000";
	private static final String PASSWORD = "frontend-password-123";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	@LocalServerPort int port;

	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("Given 실제 브라우저 호출일 때 When 가입부터 로그아웃까지 수행하면 Then CORS 인증 쿠키 Swagger 계약이 연결된다")
	void completesFrontendAuthenticationAndSwaggerFlowOverRealHttp() throws Exception {
		assertSwaggerAvailableWithoutAuthentication();

		String email = "frontend-" + UUID.randomUUID() + "@example.com";
		HttpResponse<String> signUp = send(jsonRequest("/api/v1/auth/sign-up/teachers")
			.POST(HttpRequest.BodyPublishers.ofString("""
				{"email":"%s","password":"%s","displayName":"프론트 연동 강사"}
				""".formatted(email, PASSWORD)))
			.build());
		assertCors(signUp);
		assertThat(signUp.statusCode()).isEqualTo(201);
		assertThat(signUp.headers().firstValue("Location"))
			.hasValueSatisfying(location -> assertThat(location).startsWith("/api/v1/accounts/"));
		assertThat(signUp.headers().firstValue("Access-Control-Expose-Headers"))
			.hasValueSatisfying(value -> assertThat(value).containsIgnoringCase("Location"));

		HttpResponse<String> login = send(jsonRequest("/api/v1/auth/login")
			.POST(HttpRequest.BodyPublishers.ofString("""
				{"email":"%s","password":"%s"}
				""".formatted(email, PASSWORD)))
			.build());
		assertCors(login);
		assertThat(login.statusCode()).isEqualTo(200);
		String firstAccessToken = json(login).at("/data/accessToken").asText();
		String firstRefreshCookie = responseCookie(login);
		assertThat(firstAccessToken).isNotBlank();
		assertThat(login.body()).doesNotContain("refreshToken");
		assertRefreshCookieAttributes(login);

		HttpResponse<String> protectedResponse = send(request(
			"/api/v1/dashboard/briefing?date="
				+ LocalDate.now(ZoneId.of("Asia/Seoul")))
			.header("Origin", FRONTEND_ORIGIN)
			.header("Authorization", "Bearer " + firstAccessToken)
			.GET()
			.build());
		assertCors(protectedResponse);
		assertThat(protectedResponse.statusCode()).isEqualTo(200);

		HttpResponse<String> refresh = send(request("/api/v1/auth/refresh")
			.header("Origin", FRONTEND_ORIGIN)
			.header("Cookie", firstRefreshCookie)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
		assertCors(refresh);
		assertThat(refresh.statusCode()).isEqualTo(200);
		String rotatedAccessToken = json(refresh).at("/data/accessToken").asText();
		String rotatedRefreshCookie = responseCookie(refresh);
		assertThat(rotatedAccessToken).isNotBlank().isNotEqualTo(firstAccessToken);
		assertThat(rotatedRefreshCookie).isNotEqualTo(firstRefreshCookie);

		HttpResponse<String> logout = send(request("/api/v1/auth/logout")
			.header("Origin", FRONTEND_ORIGIN)
			.header("Authorization", "Bearer " + rotatedAccessToken)
			.header("Cookie", rotatedRefreshCookie)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
		assertCors(logout);
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.headers().firstValue("Set-Cookie"))
			.hasValueSatisfying(value -> assertThat(value).contains("Max-Age=0"));

		HttpResponse<String> refreshAfterLogout = send(request("/api/v1/auth/refresh")
			.header("Origin", FRONTEND_ORIGIN)
			.header("Cookie", rotatedRefreshCookie)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
		assertCors(refreshAfterLogout);
		assertThat(refreshAfterLogout.statusCode()).isEqualTo(401);
		assertThat(json(refreshAfterLogout).get("code").asText()).isEqualTo("SESSION_INVALID");
	}

	private void assertSwaggerAvailableWithoutAuthentication() throws Exception {
		HttpResponse<String> swaggerRedirect = send(request("/swagger-ui.html").GET().build());
		assertThat(swaggerRedirect.statusCode()).isEqualTo(302);
		assertThat(swaggerRedirect.headers().firstValue("Location"))
			.hasValueSatisfying(value -> assertThat(value).contains("/swagger-ui/index.html"));

		HttpResponse<String> contract = send(request("/openapi/dashboard-api.yaml").GET().build());
		assertThat(contract.statusCode()).isEqualTo(200);
		assertThat(contract.body())
			.contains("openapi: 3.0.3")
			.contains("/auth/login:")
			.contains("/dashboard/briefing:");
	}

	private HttpRequest.Builder jsonRequest(String path) {
		return request(path)
			.header("Origin", FRONTEND_ORIGIN)
			.header("Content-Type", "application/json");
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode json(HttpResponse<String> response) throws Exception {
		return objectMapper.readTree(response.body());
	}

	private String responseCookie(HttpResponse<String> response) {
		String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
		return setCookie.substring(0, setCookie.indexOf(';'));
	}

	private void assertRefreshCookieAttributes(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Set-Cookie"))
			.hasValueSatisfying(value -> assertThat(value)
				.contains("CHECKON_REFRESH=")
				.contains("HttpOnly")
				.contains("SameSite=Lax")
				.contains("Path=/api/v1/auth")
				.doesNotContain("Secure"));
	}

	private void assertCors(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
			.contains(FRONTEND_ORIGIN);
		assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
			.contains("true");
	}
}
