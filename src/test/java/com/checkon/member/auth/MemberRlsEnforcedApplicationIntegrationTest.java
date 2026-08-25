package com.checkon.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.member.support.MemberPostgresSupport;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;

/**
 * 🔴 <b>애플리케이션을 RLS 가 실제로 걸리는 역할로 돌린다.</b>
 *
 * <p>왜 별도 클래스인가 — Testcontainers 기본 사용자는 {@code super=true bypassrls=true} 다(실측).
 * 다른 통합 테스트는 그 역할로 도는데, 그러면 <b>컨텍스트를 여는 코드를 지워도 테스트가 green</b> 이다.
 * 실제로 고의 파괴에서 그걸 확인했다 — {@code setCurrentStudent} 를 지워도 MockMvc 테스트가
 * 전부 통과했다. 정책은 있는데 애플리케이션이 정책을 만족시키는지는 증명되지 않은 상태였다.</p>
 *
 * <p>그래서 이 클래스만 {@code spring.datasource} 를 {@code NOSUPERUSER NOBYPASSRLS} 역할로
 * 바꾼다 — 운영의 {@code checkon_app} 과 같은 조건이다. Flyway 는 계속 관리자로 돈다
 * ({@code application.yaml} 이 이미 둘을 분리해 뒀다).</p>
 *
 * <p>🔴 픽스처는 관리자 커넥션으로 직접 넣는다. 앱 커넥션으로 넣으면 INSERT 정책에 걸려
 * 무엇을 검증하는지 흐려진다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class MemberRlsEnforcedApplicationIntegrationTest {

	private static final String APP_ROLE = "checkon_app_rls_test";
	private static final String APP_PASSWORD = "checkon_app_rls_test_pw";

	@Container
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		prepareRestrictedRole();
		// 앱은 제한 역할로, Flyway 는 관리자로.
		registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
		registry.add("spring.datasource.username", () -> APP_ROLE);
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.url", POSTGRESQL::getJdbcUrl);
		registry.add("spring.flyway.user", POSTGRESQL::getUsername);
		registry.add("spring.flyway.password", POSTGRESQL::getPassword);
		// 🔴 이 역할은 superuser 가 아니므로 기동 검증이 통과해야 정상이다.
		registry.add("checkon.tenant.verify-database-role", () -> "true");
	}

	private static void prepareRestrictedRole() {
		execute(
			"DROP ROLE IF EXISTS " + APP_ROLE,
			"CREATE ROLE " + APP_ROLE + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
				+ " NOINHERIT NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'",
			"GRANT USAGE ON SCHEMA public TO " + APP_ROLE,
			// 🔴 Flyway 가 아직 테이블을 안 만들었다. 그래서 기본 권한으로 미리 건다 —
			//    이후 관리자가 만드는 테이블에 자동으로 적용된다.
			"ALTER DEFAULT PRIVILEGES IN SCHEMA public"
				+ " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_ROLE,
			"ALTER DEFAULT PRIVILEGES IN SCHEMA public"
				+ " GRANT USAGE, SELECT ON SEQUENCES TO " + APP_ROLE,
			"ALTER DEFAULT PRIVILEGES IN SCHEMA public"
				+ " GRANT EXECUTE ON FUNCTIONS TO " + APP_ROLE);
	}

	private static void execute(String... statements) {
		try (Connection connection = DriverManager.getConnection(
			POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
			Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
		catch (SQLException exception) {
			throw new IllegalStateException("restricted role setup failed", exception);
		}
	}

	@Autowired MockMvc mockMvc;

	private JdbcTemplate admin;
	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID parentAccountId;
	private UUID teacherId;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource adminDataSource = new DriverManagerDataSource();
		adminDataSource.setUrl(POSTGRESQL.getJdbcUrl());
		adminDataSource.setUsername(POSTGRESQL.getUsername());
		adminDataSource.setPassword(POSTGRESQL.getPassword());
		admin = new JdbcTemplate(adminDataSource);

		MemberPostgresSupport.clearMemberFixtures(admin);

		OffsetDateTime now = OffsetDateTime.now();
		studentAccountId = insertAccount("student@example.com", "STUDENT", now);
		studentProfileId = UUID.randomUUID();
		admin.update("INSERT INTO student_profiles (id, account_id, alias, grade,"
			+ " account_linked_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			studentProfileId, studentAccountId, "김학생", (short) 2, now, now, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", studentAccountId, "김학생", now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", studentProfileId, "STU-RLS001", now);
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?, ?)",
			studentProfileId, now, now, now);

		parentAccountId = insertAccount("parent@example.com", "PARENT", now);
		admin.update("INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?)", UUID.randomUUID(), parentAccountId, now, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", parentAccountId, "박학부모", now, now);

		UUID teacherAccountId = insertAccount("teacher@example.com", "TEACHER", now);
		teacherId = UUID.randomUUID();
		admin.update("INSERT INTO teacher_profiles (id, account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?, ?)", teacherId, teacherAccountId, "김강사", now, now);
		admin.update("INSERT INTO teacher_student_relationships (id, teacher_id, student_id,"
			+ " status, started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);
		admin.update("INSERT INTO parent_teacher_relationships (id, parent_id, teacher_id,"
			+ " status, started_at, created_at) SELECT ?, id, ?, 'ACTIVE', ?, ? FROM parent_profiles"
			+ " WHERE account_id = ?", UUID.randomUUID(), teacherId, now, now, parentAccountId);
	}

	@Test
	@DisplayName("🔴 전제 — 애플리케이션 커넥션이 정말 RLS 대상이다")
	void applicationRoleIsSubjectToRowLevelSecurity() throws Exception {
		// 앱이 이 역할로 붙지 않으면 아래 세 테스트가 전부 무의미하다.
		try (Connection connection = DriverManager.getConnection(
			POSTGRESQL.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
			Statement statement = connection.createStatement()) {
			var rs = statement.executeQuery(
				"SELECT rolsuper::text||'/'||rolbypassrls::text FROM pg_roles"
					+ " WHERE rolname = current_user");
			assertThat(rs.next()).isTrue();
			assertThat(rs.getString(1)).isEqualTo("false/false");
		}
	}

	@Test
	@DisplayName("🔴 결함 2 재현 — 주체 해석이 학생 컨텍스트를 열어야 활성화가 읽힌다")
	void subjectLoaderOpensStudentContext() throws Exception {
		// setCurrentStudent 를 지우면 activationStatus 가 null 이 되고 guard 가 403 을 낸다.
		mockMvc.perform(get("/api/v1/member/auth/session").with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.activationStatus").value("ACTIVE"));
	}

	@Test
	@DisplayName("🔴 결함 1 재현 — 학부모 프로필 조회가 계정 컨텍스트 없이는 401 이 된다")
	void subjectLoaderOpensAccountContextForParent() throws Exception {
		mockMvc.perform(get("/api/v1/member/auth/session").with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("PARENT"))
			.andExpect(jsonPath("$.data.parentProfileId").isNotEmpty());
	}

	@Test
	@DisplayName("🔴 결함 3 재현 — 세션이 역할 주체를 열어야 teachers 가 채워진다")
	void sessionOpensRoleSubjectForTeacherLookup() throws Exception {
		mockMvc.perform(get("/api/v1/member/auth/session").with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.teachers.length()").value(1))
			.andExpect(jsonPath("$.data.teachers[0].teacherId").value(teacherId.toString()));

		mockMvc.perform(get("/api/v1/member/auth/session").with(parent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.teachers.length()").value(1));
	}

	@Test
	@DisplayName("🔴 활성화 폴링도 RLS 아래에서 실제 행을 읽는다")
	void activationStatusReadsRealRowUnderRls() throws Exception {
		mockMvc.perform(get("/api/v1/member/auth/students/activation-status").with(student()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.studentPublicId").value("STU-RLS001"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(principalOf(studentAccountId, AccountRole.STUDENT));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor parent() {
		return authentication(principalOf(parentAccountId, AccountRole.PARENT));
	}

	private Authentication principalOf(UUID accountId, AccountRole role) {
		AuthenticatedAccount account = new AuthenticatedAccount(
			accountId, role, null, UUID.randomUUID());
		return new UsernamePasswordAuthenticationToken(
			account, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
	}

	private UUID insertAccount(String email, String role, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		admin.update(
			"INSERT INTO accounts (id, email, role, status, created_at) VALUES (?, ?, ?, ?, ?)",
			id, email, role, "ACTIVE", now);
		return id;
	}
}
