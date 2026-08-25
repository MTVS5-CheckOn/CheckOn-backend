package com.checkon.member.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import com.checkon.member.support.MemberPostgresSupport;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RLS 가 <b>실제로 강제되는</b> 조건에서 member 의 컨텍스트 사용을 증명한다.
 *
 * <p>🔴 <b>왜 별도 역할을 만드나</b> — Testcontainers 의 기본 사용자는
 * {@code super=true bypassrls=true} 다(실측). 그 역할로는 RLS 가 통째로 우회되므로
 * 「컨텍스트를 안 열면 0행」이 <b>증명되지 않는다</b>. 정책을 검증하려면
 * {@code NOSUPERUSER NOBYPASSRLS} 역할로 읽어야 한다 — 운영의 {@code checkon_app} 과 같은 조건이다.</p>
 *
 * <p>🔴 픽스처는 superuser 로 넣고 <b>검증만</b> 제한 역할로 한다. 픽스처까지 제한 역할로 넣으면
 * INSERT 정책에 걸려 테스트가 무엇을 검증하는지 흐려진다.</p>
 */
@SpringBootTest(properties = {
	"checkon.security.test-authentication.enabled=true",
	"checkon.auth.allowed-origins=http://localhost:3000",
	"spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("dev")
class MemberRlsContextIntegrationTest extends MemberPostgresSupport {

	private static final String RESTRICTED_ROLE = "member_rls_probe";
	private static final String RESTRICTED_PASSWORD = "member_rls_probe_pw";

	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TransactionTemplate transactionTemplate;

	private JdbcTemplate restricted;
	private TransactionTemplate restrictedTransaction;

	private UUID studentAccountId;
	private UUID studentProfileId;
	private UUID parentAccountId;
	private UUID parentProfileId;
	private UUID teacherId;

	@BeforeEach
	void setUp() {
		createRestrictedRole();
		restricted = new JdbcTemplate(restrictedDataSource());
		restrictedTransaction = new TransactionTemplate(
			new org.springframework.jdbc.support.JdbcTransactionManager(
				restricted.getDataSource()));

		jdbcTemplate.update("DELETE FROM member_student_activation");
		jdbcTemplate.update("DELETE FROM member_display_names");
		jdbcTemplate.update("DELETE FROM member_student_public_ids");
		jdbcTemplate.update("DELETE FROM teacher_student_relationships");
		jdbcTemplate.update("DELETE FROM parent_teacher_relationships");
		jdbcTemplate.update("DELETE FROM student_profiles");
		jdbcTemplate.update("DELETE FROM parent_profiles");
		jdbcTemplate.update("DELETE FROM teacher_profiles");
		jdbcTemplate.update("DELETE FROM accounts");

		OffsetDateTime now = OffsetDateTime.now();
		studentAccountId = insertAccount("student@example.com", "STUDENT", "ACTIVE", now);
		studentProfileId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO student_profiles (id, account_id, alias, grade, account_linked_at,"
				+ " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			studentProfileId, studentAccountId, "학생", (short) 1, now, now, now);

		parentAccountId = insertAccount("parent@example.com", "PARENT", "ACTIVE", now);
		parentProfileId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO parent_profiles (id, account_id, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?)",
			parentProfileId, parentAccountId, now, now);

		UUID teacherAccountId = insertAccount("teacher@example.com", "TEACHER", "ACTIVE", now);
		teacherId = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO teacher_profiles (id, account_id, display_name, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?)",
			teacherId, teacherAccountId, "김강사", now, now);

		jdbcTemplate.update(
			"INSERT INTO member_display_names (account_id, display_name, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?)", studentAccountId, "학생", now, now);
		jdbcTemplate.update(
			"INSERT INTO member_student_activation (student_id, status, activated_at,"
				+ " created_at, updated_at) VALUES (?, 'PENDING_PARENT_LINK', NULL, ?, ?)",
			studentProfileId, now, now);
		jdbcTemplate.update(
			"INSERT INTO teacher_student_relationships (id, teacher_id, student_id, status,"
				+ " started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), teacherId, studentProfileId, now, now);
		jdbcTemplate.update(
			"INSERT INTO parent_teacher_relationships (id, parent_id, teacher_id, status,"
				+ " started_at, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
			UUID.randomUUID(), parentProfileId, teacherId, now, now);
	}

	@Test
	@DisplayName("🔴 제한 역할에는 RLS 가 실제로 걸린다 — 이 전제가 깨지면 아래 전부 무의미하다")
	void restrictedRoleIsSubjectToRowLevelSecurity() {
		String flags = restricted.queryForObject(
			"SELECT rolsuper||'/'||rolbypassrls FROM pg_roles WHERE rolname = current_user",
			String.class);

		assertThat(flags)
			.as("superuser 나 BYPASSRLS 면 정책이 우회돼 아래 단언이 전부 참이 된다")
			.isEqualTo("false/false");
	}

	@Test
	@DisplayName("🔴 결함 1 — 컨텍스트 없이 parent_profiles 를 읽으면 예외가 아니라 0행이다")
	void parentProfileIsInvisibleWithoutContext() {
		List<String> withoutContext = restrictedTransaction.execute(status ->
			restricted.queryForList("SELECT id::text FROM parent_profiles", String.class));

		assertThat(withoutContext)
			.as("조용히 0행이라 401 'parent profile is missing' 으로 위장된다")
			.isEmpty();

		List<String> withContext = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			return restricted.queryForList("SELECT id::text FROM parent_profiles", String.class);
		});

		// 🔴 count > 0 이 아니라 기대한 그 행인지까지 본다.
		assertThat(withContext).containsExactly(parentProfileId.toString());
	}

	@Test
	@DisplayName("🔴 결함 2 — 계정만 열면 member_student_activation 이 0행이다")
	void activationNeedsStudentSubjectNotJustAccount() {
		List<String> accountOnly = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			return restricted.queryForList(
				"SELECT status FROM member_student_activation", String.class);
		});

		assertThat(accountOnly)
			.as("계정만 열면 0행 → activationStatus=null → guard 가 fail-closed 로 403")
			.isEmpty();

		List<String> withStudent = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			setConfig("checkon.current_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT status FROM member_student_activation", String.class);
		});

		assertThat(withStudent).containsExactly("PENDING_PARENT_LINK");
	}

	@Test
	@DisplayName("🔴 결함 3 — 계정만 열면 강사 관계가 빈 배열이다 (200 이라 더 조용하다)")
	void teacherRelationshipsNeedRoleSubject() {
		List<String> accountOnly = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_account_id", studentAccountId);
			return restricted.queryForList(
				"SELECT teacher_id::text FROM teacher_student_relationships", String.class);
		});

		assertThat(accountOnly).as("빈 배열도 200 이라 오류로 드러나지 않는다").isEmpty();

		List<String> withStudent = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT teacher_id::text FROM teacher_student_relationships", String.class);
		});

		assertThat(withStudent).containsExactly(teacherId.toString());

		List<String> parentSide = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_parent_id", parentProfileId);
			return restricted.queryForList(
				"SELECT teacher_id::text FROM parent_teacher_relationships", String.class);
		});

		assertThat(parentSide).containsExactly(teacherId.toString());
	}

	@Test
	@DisplayName("🔴 §6-4-4 — 컨텍스트는 트랜잭션을 넘지 않는다")
	void contextDoesNotSurviveTransaction() {
		restrictedTransaction.executeWithoutResult(status -> {
			setConfig("checkon.current_account_id", parentAccountId);
			assertThat(restricted.queryForList("SELECT id::text FROM parent_profiles", String.class))
				.as("같은 트랜잭션 안에서는 보인다")
				.hasSize(1);
		});

		List<String> nextTransaction = restrictedTransaction.execute(status ->
			restricted.queryForList("SELECT id::text FROM parent_profiles", String.class));

		assertThat(nextTransaction)
			.as("앞 트랜잭션이 열었다고 다음 트랜잭션이 보면 격리가 깨진 것이다")
			.isEmpty();
	}

	@Test
	@DisplayName("🔴 MB-31 — 학부모는 범위를 열어야 자녀 활성화를 본다")
	void parentSeesChildActivationOnlyThroughScope() {
		List<String> parentOnly = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_parent_id", parentProfileId);
			return restricted.queryForList(
				"SELECT status FROM member_student_activation", String.class);
		});

		assertThat(parentOnly).as("주체만으로는 못 본다 — 범위가 있어야 한다").isEmpty();

		List<String> withScope = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT status FROM member_student_activation", String.class);
		});

		assertThat(withScope).containsExactly("PENDING_PARENT_LINK");
	}

	@Test
	@DisplayName("🔴 MB-31 — 학부모가 남의 자녀 id 를 범위에 넣어도 그 행은 안 보인다")
	void parentScopeDoesNotOpenOtherChildren() {
		UUID strangerProfileId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now();
		UUID strangerAccountId = insertAccount("stranger@example.com", "STUDENT", "ACTIVE", now);
		jdbcTemplate.update(
			"INSERT INTO student_profiles (id, account_id, alias, account_linked_at,"
				+ " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			strangerProfileId, strangerAccountId, "남의자녀", now, now, now);
		jdbcTemplate.update(
			"INSERT INTO member_student_activation (student_id, status, activated_at,"
				+ " created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?, ?)",
			strangerProfileId, now, now, now);

		List<String> visible = restrictedTransaction.execute(status -> {
			setConfig("checkon.current_parent_id", parentProfileId);
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT student_id::text FROM member_student_activation", String.class);
		});

		// 🔴 범위에 넣은 그 자녀 하나만. 남의 자녀가 섞이면 범위 변수가 우회로가 된다.
		assertThat(visible).containsExactly(studentProfileId.toString());
	}

	@Test
	@DisplayName("🔴 MB-33 — 강사 정책은 두 주인의 값이 필요해 지금은 켜지지 않는다")
	void teacherScopePolicyCannotBeOpenedByMemberAlone() {
		List<String> scopeOnly = restrictedTransaction.execute(status -> {
			// member 가 넣을 수 있는 값은 이것뿐이다. current_teacher_id 는 절대 규칙 3 으로 금지.
			setConfig("checkon.scope_student_id", studentProfileId);
			return restricted.queryForList(
				"SELECT status FROM member_student_activation", String.class);
		});

		assertThat(scopeOnly)
			.as("member 단독으로 열리면 절대 규칙 3 을 우회한 것이다")
			.isEmpty();
	}

	private void setConfig(String name, UUID value) {
		restricted.queryForObject(
			"SELECT set_config(?, ?, true)", String.class, name, value.toString());
	}

	private UUID insertAccount(String email, String role, String status, OffsetDateTime now) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
			"INSERT INTO accounts (id, email, role, status, created_at) VALUES (?, ?, ?, ?, ?)",
			id, email, role, status, now);
		return id;
	}

	private void createRestrictedRole() {
		Integer exists = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, RESTRICTED_ROLE);
		if (exists == null || exists == 0) {
			jdbcTemplate.execute("CREATE ROLE " + RESTRICTED_ROLE
				+ " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS"
				+ " PASSWORD '" + RESTRICTED_PASSWORD + "'");
		}
		jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RESTRICTED_ROLE);
		jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public"
			+ " TO " + RESTRICTED_ROLE);
		jdbcTemplate.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RESTRICTED_ROLE);
	}

	private DriverManagerDataSource restrictedDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(RESTRICTED_ROLE);
		dataSource.setPassword(RESTRICTED_PASSWORD);
		return dataSource;
	}
}
