package com.checkon.account.presentation;

import java.net.HttpCookie;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.application.TeacherSignUpService;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.account.infrastructure.security.AuthenticatedAccountService;
import com.checkon.account.infrastructure.security.RefreshTokenService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 PostgreSQL에서 가입된 강사의 로그인부터 회전·로그아웃까지 검증한다.
 *
 * <p>이 테스트는 HTTP 성공만 보는 대신 Flyway/Hibernate validate, BCrypt 검증,
 * JWT 최소 claim, 원문 미저장과 세션 상태 전이를 함께 증명한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class AuthenticationIntegrationTest {

	private static final String EMAIL = "teacher@example.com";
	private static final String PASSWORD = "safe-password-123";
	private static final String COOKIE_NAME = "CHECKON_REFRESH";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TeacherSignUpService signUpService;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtDecoder jwtDecoder;
	@Autowired RefreshTokenService refreshTokenService;
	@Autowired AuthenticatedAccountService authenticatedAccountService;
	@Autowired ObjectMapper objectMapper;

	private UUID accountId;
	private UUID teacherProfileId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM authentication_sessions");
		jdbcTemplate.update("DELETE FROM teacher_profiles");
		jdbcTemplate.update("DELETE FROM account_password_credentials");
		jdbcTemplate.update("DELETE FROM accounts");
		var signUp = signUpService.signUp(EMAIL, PASSWORD, "김서현");
		accountId = signUp.accountId();
		teacherProfileId = signUp.teacherId();
	}

	@Test
	void logsInCaseInsensitivelyAndStoresOnlyRefreshHash() throws Exception {
		MvcResult login = login(" Teacher@Example.COM ", PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.account.id").value(accountId.toString()))
			.andExpect(jsonPath("$.data.account.role").value("TEACHER"))
			.andExpect(jsonPath("$.data.account.teacherProfileId")
				.value(teacherProfileId.toString()))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE,
				org.hamcrest.Matchers.allOf(
					org.hamcrest.Matchers.containsString("HttpOnly"),
					org.hamcrest.Matchers.containsString("SameSite=Lax"),
					org.hamcrest.Matchers.containsString("Path=/api/v1/auth")
				)))
			.andReturn();

		JsonNode body = body(login);
		String accessToken = body.at("/data/accessToken").asText();
		String rawRefreshToken = cookieValue(login);
		String storedHash = jdbcTemplate.queryForObject(
			"SELECT refresh_token_hash FROM authentication_sessions",
			String.class
		);
		String passwordHash = jdbcTemplate.queryForObject(
			"SELECT password_hash FROM account_password_credentials",
			String.class
		);

		assertThat(rawRefreshToken).isNotBlank();
		assertThat(storedHash)
			.isNotEqualTo(rawRefreshToken)
			.isEqualTo(refreshTokenService.hash(rawRefreshToken));
		assertThat(passwordHash).isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
		assertThat(login.getResponse().getContentAsString())
			.doesNotContain(PASSWORD)
			.doesNotContain(rawRefreshToken);

		Jwt jwt = jwtDecoder.decode(accessToken);
		assertThat(jwt.getClaims().keySet()).isEqualTo(
			Set.of("sub", "role", "sid", "iat", "exp", "jti")
		);
		assertThat(jwt.getSubject()).isEqualTo(accountId.toString());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("TEACHER");

		UsernamePasswordAuthenticationToken authentication =
			authenticatedAccountService.authenticate(jwt);
		AuthenticatedAccount principal =
			(AuthenticatedAccount) authentication.getPrincipal();
		assertThat(principal.accountId()).isEqualTo(accountId);
		assertThat(principal.teacherProfileId()).isEqualTo(teacherProfileId);
	}

	@Test
	void usesTheSameFailureForUnknownEmailAndWrongPassword() throws Exception {
		String wrongPasswordResponse = login(EMAIL, "wrong-password")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andReturn().getResponse().getContentAsString();
		String unknownEmailResponse = login("unknown@example.com", "wrong-password")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andReturn().getResponse().getContentAsString();

		assertThat(unknownEmailResponse).isEqualTo(wrongPasswordResponse);
		assertThat(sessionCount()).isZero();
	}

	@Test
	void rejectsWithdrawnAndSuspendedAccounts() throws Exception {
		MvcResult activeLogin = login(EMAIL, PASSWORD)
			.andExpect(status().isOk())
			.andReturn();
		String suspendedRefreshToken = cookieValue(activeLogin);
		String suspendedAccessToken = body(activeLogin)
			.at("/data/accessToken")
			.asText();
		jdbcTemplate.update(
			"UPDATE accounts SET status = 'SUSPENDED' WHERE id = ?",
			accountId
		);
		login(EMAIL, PASSWORD)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
		refresh(suspendedRefreshToken)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
		mockMvc.perform(post("/api/v1/auth/logout")
				.header(
					HttpHeaders.AUTHORIZATION,
					"Bearer " + suspendedAccessToken
				))
			.andExpect(status().isUnauthorized());

		jdbcTemplate.update(
			"""
			UPDATE accounts
			SET status = 'WITHDRAWN', withdrawn_at = now()
			WHERE id = ?
			""",
			accountId
		);
		login(EMAIL, PASSWORD)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
		refresh(suspendedRefreshToken)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
		assertThat(sessionCount()).isEqualTo(1);
	}

	@Test
	void rotatesRefreshTokenAndRejectsThePreviousToken() throws Exception {
		MvcResult login = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
		String firstToken = cookieValue(login);

		MvcResult refreshed = refresh(firstToken)
			.andExpect(status().isOk())
			.andReturn();
		String secondToken = cookieValue(refreshed);
		assertThat(secondToken).isNotEqualTo(firstToken);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT refresh_token_hash FROM authentication_sessions",
			String.class
		)).isEqualTo(refreshTokenService.hash(secondToken));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT rotation_count FROM authentication_sessions",
			Integer.class
		)).isEqualTo(1);

		refresh(firstToken)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	@Test
	void logoutRevokesCurrentSessionAndBlocksRefreshAndAccess() throws Exception {
		MvcResult login = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
		String accessToken = body(login).at("/data/accessToken").asText();
		String refreshToken = cookieValue(login);

		mockMvc.perform(post("/api/v1/auth/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE,
				org.hamcrest.Matchers.containsString("Max-Age=0")));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM authentication_sessions",
			String.class
		)).isEqualTo("REVOKED");
		refresh(refreshToken).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/auth/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsExpiredSessionAndDisallowedBrowserOrigin() throws Exception {
		MvcResult login = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
		String refreshToken = cookieValue(login);
		jdbcTemplate.update("""
			UPDATE authentication_sessions
			SET issued_at = now() - interval '15 days',
			    expires_at = now() - interval '1 day'
			""");

		refresh(refreshToken)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SESSION_INVALID"));

		mockMvc.perform(post("/api/v1/auth/refresh")
				.header(HttpHeaders.ORIGIN, "https://attacker.example")
				.cookie(new Cookie(COOKIE_NAME, refreshToken)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
	}

	private org.springframework.test.web.servlet.ResultActions login(
		String email,
		String password
	) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsBytes(Map.of(
				"email", email,
				"password", password
			))));
	}

	private org.springframework.test.web.servlet.ResultActions refresh(String token)
		throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh")
			.cookie(new Cookie(COOKIE_NAME, token)));
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private String cookieValue(MvcResult result) {
		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		return HttpCookie.parse(setCookie).getFirst().getValue();
	}

	private int sessionCount() {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM authentication_sessions",
			Integer.class
		);
	}
}
