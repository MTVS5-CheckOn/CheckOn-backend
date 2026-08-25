package com.checkon.member.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;

/**
 * member 보안 체인이 실제로 앞서 먹는지, 그리고 승우님 체인을 건드리지 않는지 본다.
 *
 * <p>🔴 dev 프로파일로 띄운다. {@code DevelopmentTestAuthenticationFilter} 가 TEACHER 를 자동
 * 주입하는 환경에서도 member 경로가 401 이어야 한다는 것이 이 PR 의 핵심 단언이다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class MemberSecurityChainIntegrationTest {

	private static final String PING = "/api/v1/member/ping";
	private static final String PARENT_PATH = "/api/v1/member/parents/me/profile";
	private static final String STUDENT_PATH = "/api/v1/member/students/me/home";
	private static final String TEACHER_API = "/api/v1/dashboard/briefing";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbcTemplate;

	private UUID studentAccountId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM student_profiles");
		jdbcTemplate.update("DELETE FROM parent_profiles");
		jdbcTemplate.update("DELETE FROM accounts WHERE role <> 'TEACHER'");
		studentAccountId = insertStudentWithProfile("student@example.com");
	}

	@Test
	@DisplayName("토큰 없이 ping 을 부르면 401 과 member 오류 봉투가 온다")
	void pingRequiresAuthentication() throws Exception {
		mockMvc.perform(get(PING))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
			.andExpect(jsonPath("$.error.message").exists());
	}

	@Test
	@DisplayName("🔴 dev 의 TEACHER 자동 주입이 member 체인으로 새지 않는다")
	void devTestAuthenticationDoesNotLeakIntoMemberChain() throws Exception {
		// 같은 컨텍스트에서 기존 체인은 자동 주입을 받아 인증을 통과한다.
		MvcResult teacherApi = mockMvc.perform(get(TEACHER_API)).andReturn();
		assertThat(teacherApi.getResponse().getStatus())
			.as("기존 강사 API 는 dev 자동 주입으로 인증을 통과한다")
			.isNotIn(401, 403);

		// member 경로는 같은 조건에서도 인증을 요구해야 한다. 200 이면 필터가 샌 것이다.
		mockMvc.perform(get(PING)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("학생 토큰으로 학부모 경로를 부르면 403 ROLE_FORBIDDEN")
	void studentTokenCannotReachParentPaths() throws Exception {
		mockMvc.perform(get(PARENT_PATH).with(authentication(principal(
				studentAccountId, AccountRole.STUDENT))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"));
	}

	@Test
	@DisplayName("강사 토큰은 member 학생 경로에서 거부된다")
	void teacherTokenIsRejected() throws Exception {
		mockMvc.perform(get(STUDENT_PATH).with(authentication(principal(
				UUID.randomUUID(), AccountRole.TEACHER))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"));
	}

	@Test
	@DisplayName("학생 주체로 ping 을 부르면 주체 해석이 학생 프로필까지 푼다")
	void pingResolvesMemberSubject() throws Exception {
		mockMvc.perform(get(PING).with(authentication(principal(
				studentAccountId, AccountRole.STUDENT))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("STUDENT"))
			.andExpect(jsonPath("$.data.accountId").value(studentAccountId.toString()));
	}

	@Test
	@DisplayName("🔴 기존 강사 API 의 응답 형식이 member 봉투로 바뀌지 않는다")
	void existingTeacherApiUnaffected() throws Exception {
		mockMvc.perform(get(TEACHER_API))
			.andExpect(jsonPath("$.error").doesNotExist())
			.andExpect(jsonPath("$.code").exists());
	}

	private Authentication principal(UUID accountId, AccountRole role) {
		AuthenticatedAccount account = new AuthenticatedAccount(
			accountId, role, null, UUID.randomUUID());
		return new UsernamePasswordAuthenticationToken(
			account, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
	}

	private UUID insertStudentWithProfile(String email) {
		UUID accountId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now();
		jdbcTemplate.update(
			"INSERT INTO accounts (id, email, role, status, created_at) VALUES (?, ?, ?, ?, ?)",
			accountId, email, "STUDENT", "ACTIVE", now);
		jdbcTemplate.update(
			"INSERT INTO student_profiles (id, account_id, alias, account_linked_at, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(), accountId, "학생", now, now, now);
		return accountId;
	}
}
