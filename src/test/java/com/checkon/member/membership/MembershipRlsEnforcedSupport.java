package com.checkon.member.membership;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.account.domain.AccountRole;
import com.checkon.account.infrastructure.security.AuthenticatedAccount;
import com.checkon.member.membership.application.InvitationCodeHasher;
import com.checkon.member.support.MemberPostgresSupport;

/**
 * membership 통합 테스트의 공통 토대. 🔴 <b>애플리케이션을 RLS 가 실제로 걸리는 역할로 돌린다.</b>
 *
 * <p>왜 필수인가 — 컨테이너 기본 사용자는 {@code super=true bypassrls=true} 라 정책이 통째로
 * 우회된다(MB-34). 그 역할로 돌리면 이 PR 의 단언이 <b>반대 결과를 참으로 만든다</b>:
 * {@code existsActiveParentLink} 가 <b>남의</b> 학부모 연결까지 보게 되어, 「다른 학부모가 이미
 * 등록함」이 사전 조회에서 걸리고 unique index 경로가 아예 실행되지 않는다. 즉 이 스위트를
 * superuser 로 돌리면 <b>테스트는 green 인데 검증한 것은 실제 동작이 아니다.</b></p>
 *
 * <p>🔴 {@code @Testcontainers}/{@code @Container} 를 쓰지 않는다 — 그 확장은 클래스가 끝나면
 * 컨테이너를 멈춰서, 같은 컨테이너를 공유하는 뒤 클래스를 죽인다(PR3 실측 19건).
 * 수명을 JVM 에 맡기고 정리는 Ryuk 에게 넘긴다.</p>
 */
public abstract class MembershipRlsEnforcedSupport {

	protected static final String APP_ROLE = "membership_rls_app";
	protected static final String APP_PASSWORD = "membership_rls_app_pw";

	protected static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer("postgres:18.4");

	static {
		POSTGRES.start();
		prepareRestrictedRole();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		// 앱은 제한 역할로, Flyway 는 관리자로 — 운영의 checkon_app / checkonAdmin 과 같은 조건.
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", () -> APP_ROLE);
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
		registry.add("spring.flyway.user", POSTGRES::getUsername);
		registry.add("spring.flyway.password", POSTGRES::getPassword);
		registry.add("checkon.tenant.verify-database-role", () -> "true");
	}

	private static void prepareRestrictedRole() {
		execute(
			"DROP ROLE IF EXISTS " + APP_ROLE,
			"CREATE ROLE " + APP_ROLE + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
				+ " NOINHERIT NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'",
			"GRANT USAGE ON SCHEMA public TO " + APP_ROLE,
			// 🔴 Flyway 가 아직 테이블을 안 만들었다. 기본 권한으로 미리 걸어 둔다.
			"ALTER DEFAULT PRIVILEGES IN SCHEMA public"
				+ " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_ROLE,
			"ALTER DEFAULT PRIVILEGES IN SCHEMA public"
				+ " GRANT USAGE, SELECT ON SEQUENCES TO " + APP_ROLE,
			"ALTER DEFAULT PRIVILEGES IN SCHEMA public"
				+ " GRANT EXECUTE ON FUNCTIONS TO " + APP_ROLE);
	}

	private static void execute(String... statements) {
		try (Connection connection = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
		catch (SQLException exception) {
			throw new IllegalStateException("restricted role setup failed", exception);
		}
	}

	/** 관리자 커넥션. 🔴 픽스처 삽입 전용이다 — 앱 경로 검증에 쓰면 무엇을 쟀는지 흐려진다. */
	protected static JdbcTemplate adminJdbcTemplate() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return new JdbcTemplate(dataSource);
	}

	/** 🔴 전제 단언용. {@code false/false} 가 아니면 이 클래스의 RLS 단언은 전부 무의미하다. */
	protected static String applicationRolePrivileges() {
		try (Connection connection = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
			Statement statement = connection.createStatement()) {
			var resultSet = statement.executeQuery(
				"SELECT rolsuper::text||'/'||rolbypassrls::text FROM pg_roles"
					+ " WHERE rolname = current_user");
			return resultSet.next() ? resultSet.getString(1) : null;
		}
		catch (SQLException exception) {
			throw new IllegalStateException("privilege probe failed", exception);
		}
	}

	protected static void clearFixtures(JdbcTemplate admin) {
		MemberPostgresSupport.clearMemberFixtures(admin);
	}

	// ──────────────────────────── 픽스처 ────────────────────────────

	protected static UUID insertAccount(
		JdbcTemplate admin, String email, String role, OffsetDateTime now
	) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO accounts (id, email, role, status, created_at)"
			+ " VALUES (?, ?, ?, 'ACTIVE', ?)", id, email, role, now);
		return id;
	}

	/** 학생 계정 + 프로필 + 표시 이름 + 공개 ID + 활성화(대기) 한 벌. */
	protected static UUID insertStudent(
		JdbcTemplate admin, UUID accountId, StudentFixture fixture, OffsetDateTime now
	) {
		UUID profileId = MemberPostgresSupport.insertStudentProfile(
			admin, accountId, fixture.alias(), fixture.grade(), now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", accountId, fixture.displayName(), now, now);
		admin.update("INSERT INTO member_student_public_ids (student_id, public_id, issued_at)"
			+ " VALUES (?, ?, ?)", profileId, fixture.publicId(), now);
		admin.update("INSERT INTO member_student_activation (student_id, status, activated_at,"
			+ " created_at, updated_at) VALUES (?, 'PENDING_PARENT_LINK', NULL, ?, ?)",
			profileId, now, now);
		return profileId;
	}

	protected static UUID insertParent(
		JdbcTemplate admin, UUID accountId, String displayName, OffsetDateTime now
	) {
		UUID profileId = UUID.randomUUID();
		admin.update("INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
			+ " VALUES (?, ?, ?, ?)", profileId, accountId, now, now);
		admin.update("INSERT INTO member_display_names (account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?)", accountId, displayName, now, now);
		return profileId;
	}

	protected static UUID insertTeacher(
		JdbcTemplate admin, String email, String displayName, OffsetDateTime now
	) {
		UUID accountId = insertAccount(admin, email, "TEACHER", now);
		UUID teacherId = UUID.randomUUID();
		admin.update("INSERT INTO teacher_profiles (id, account_id, display_name, created_at,"
			+ " updated_at) VALUES (?, ?, ?, ?, ?)", teacherId, accountId, displayName, now, now);
		return teacherId;
	}

	/**
	 * 초대 코드를 DB 에 직접 넣는다.
	 *
	 * <p>🔴 강사측 <b>발급 API 가 없어서</b> 그렇다 — 발급은 roster(강사) 경계 일이고 member 는
	 * 무접촉이다. MB-35 로 등재했다.</p>
	 */
	protected static UUID insertInvitation(JdbcTemplate admin, InvitationFixture fixture) {
		UUID id = UUID.randomUUID();
		admin.update("INSERT INTO member_invitation_codes (id, teacher_id, target_role,"
			+ " code_hash, max_claims, expires_at, revoked_at, created_at)"
			+ " VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
			id, fixture.teacherId(), fixture.targetRole(),
			InvitationCodeHasher.hash(fixture.code()),
			fixture.expiresAt(), fixture.revokedAt(), fixture.createdAt());
		return id;
	}

	protected static Authentication principalOf(UUID accountId, AccountRole role) {
		AuthenticatedAccount account =
			new AuthenticatedAccount(accountId, role, null, UUID.randomUUID());
		return new UsernamePasswordAuthenticationToken(account, null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
	}

	/** 학생 픽스처 한 벌. 파라미터가 많아 record 로 묶는다(코드 규칙 §3-3). */
	protected record StudentFixture(
		String alias, String displayName, Integer grade, String publicId
	) {
	}

	protected record InvitationFixture(
		UUID teacherId,
		String targetRole,
		String code,
		OffsetDateTime expiresAt,
		OffsetDateTime revokedAt,
		OffsetDateTime createdAt
	) {
	}
}
