package com.checkon.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.support.RosterTestFixture;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeacherTenantRowLevelSecurityIntegrationTest {

	private static final UUID TEACHER_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001001");
	private static final UUID TEACHER_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001002");
	private static final UUID STUDENT_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001011");
	private static final UUID STUDENT_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001012");
	private static final UUID CLASS_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001021");
	private static final UUID CLASS_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001022");
	private static final UUID RELATIONSHIP_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001031");
	private static final UUID RELATIONSHIP_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001032");
	private static final UUID RUN_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001041");
	private static final UUID RUN_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001042");
	private static final UUID ATTEMPT_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001051");
	private static final UUID ATTEMPT_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001052");
	private static final UUID SIGNAL_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001061");
	private static final UUID SIGNAL_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001062");
	private static final String RESTRICTED_ROLE = "checkon_rls_test_runtime";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	@Autowired
	JdbcTemplate administrator;

	private HikariDataSource restrictedDataSource;

	@BeforeAll
	void prepareRestrictedRuntimeRoleAndFixtures() {
		String password = UUID.randomUUID().toString();
		// 이 로그인 역할은 컨테이너 수명 안에서만 존재한다. 제한 역할로 테스트해야
		// Testcontainers의 슈퍼유저와 테이블 소유자 RLS 우회를 피할 수 있다.
		administrator.execute("""
			CREATE ROLE checkon_rls_test_runtime
			LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS
			PASSWORD '%s'
			""".formatted(password));
		administrator.execute("GRANT USAGE ON SCHEMA public TO " + RESTRICTED_ROLE);
		administrator.execute("""
			GRANT SELECT, INSERT, UPDATE, DELETE
			ON class_groups,
			   teacher_student_relationships,
			   class_enrollments,
			   detection_runs,
			   detection_request_attempts,
			   detection_signal_results,
			   detection_result_evidence
			TO checkon_rls_test_runtime
			""");
		administrator.execute("""
			GRANT EXECUTE ON FUNCTION current_checkon_teacher_id()
			TO checkon_rls_test_runtime
			""");

		RosterTestFixture.insertTeacher(administrator, TEACHER_A);
		RosterTestFixture.insertTeacher(administrator, TEACHER_B);
		insertFixtures();

		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRESQL.getJdbcUrl());
		config.setUsername(RESTRICTED_ROLE);
		config.setPassword(password);
		config.setMaximumPoolSize(1);
		config.setMinimumIdle(1);
		config.setAutoCommit(false);
		config.setPoolName("restricted-rls-test-pool");
		restrictedDataSource = new HikariDataSource(config);
	}

	@AfterAll
	void closeRestrictedPool() {
		if (restrictedDataSource != null) {
			restrictedDataSource.close();
		}
	}

	@Test
	void enablesAndForcesRlsWithPoliciesForARestrictedNonOwnerRole() throws Exception {
		try (Connection connection = restrictedDataSource.getConnection();
			 Statement statement = connection.createStatement()) {
			try (ResultSet role = statement.executeQuery("""
				SELECT current_user, rolsuper, rolbypassrls
				FROM pg_roles
				WHERE rolname = current_user
				""")) {
				assertThat(role.next()).isTrue();
				assertThat(role.getString("current_user")).isEqualTo(RESTRICTED_ROLE);
				assertThat(role.getBoolean("rolsuper")).isFalse();
				assertThat(role.getBoolean("rolbypassrls")).isFalse();
			}
			assertThat(queryInt(statement, """
				SELECT count(*)
				FROM pg_class table_metadata
				JOIN pg_namespace schema_metadata
				  ON schema_metadata.oid = table_metadata.relnamespace
				WHERE schema_metadata.nspname = 'public'
				  AND table_metadata.relname IN (
				    'class_groups',
				    'teacher_student_relationships',
				    'class_enrollments',
				    'detection_runs',
				    'detection_request_attempts',
				    'detection_signal_results',
				    'detection_result_evidence'
				  )
				  AND table_metadata.relrowsecurity
				  AND table_metadata.relforcerowsecurity
				""")).isEqualTo(7);
			assertThat(queryInt(statement, """
				SELECT count(*) FROM pg_policies
				WHERE schemaname = 'public'
				  AND tablename IN (
				    'class_groups',
				    'teacher_student_relationships',
				    'class_enrollments',
				    'detection_runs',
				    'detection_request_attempts',
				    'detection_signal_results',
				    'detection_result_evidence'
				  )
				""")).isEqualTo(28);
			assertThat(queryInt(statement, """
				SELECT count(*)
				FROM pg_class table_metadata
				JOIN pg_namespace schema_metadata
				  ON schema_metadata.oid = table_metadata.relnamespace
				WHERE schema_metadata.nspname = 'public'
				  AND table_metadata.relname = 'class_groups'
				  AND pg_get_userbyid(table_metadata.relowner) = current_user
				""")).isZero();
			connection.rollback();
		}
	}

	@Test
	void deniesEveryOperationWithoutTenantAndIsolatesDirectAndInheritedRows()
		throws Exception {
		try (Connection connection = restrictedDataSource.getConnection()) {
			assertThat(count(connection, "class_groups")).isZero();
			assertThat(count(connection, "detection_request_attempts")).isZero();
			assertThat(update(connection,
				"UPDATE class_groups SET name = 'blocked' WHERE id = ?", CLASS_A
			)).isZero();
			assertThat(update(connection,
				"DELETE FROM class_groups WHERE id = ?", CLASS_A
			)).isZero();
			assertThatThrownBy(() -> insertClass(
				connection,
				UUID.randomUUID(),
				TEACHER_A,
				"tenant context missing"
			)).isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
			connection.rollback();

			setTeacher(connection, TEACHER_A);
			assertThat(ids(connection, "class_groups")).containsExactly(CLASS_A);
			assertThat(ids(connection, "teacher_student_relationships"))
				.containsExactly(RELATIONSHIP_A);
			assertThat(ids(connection, "detection_runs")).containsExactly(RUN_A);
			assertThat(ids(connection, "detection_request_attempts"))
				.containsExactly(ATTEMPT_A);
			assertThat(ids(connection, "detection_signal_results"))
				.containsExactly(SIGNAL_A);
			assertThat(count(connection, "detection_result_evidence")).isEqualTo(1);
			UUID ownClass = UUID.randomUUID();
			insertClass(connection, ownClass, TEACHER_A, "allowed own class");
			assertThat(selectById(connection, "class_groups", ownClass)).isEqualTo(1);
			assertThat(selectById(connection, "class_groups", CLASS_B)).isZero();
			assertThat(update(connection,
				"UPDATE class_groups SET name = 'blocked' WHERE id = ?", CLASS_B
			)).isZero();
			assertThat(update(connection,
				"DELETE FROM class_groups WHERE id = ?", CLASS_B
			)).isZero();
			assertThat(update(connection,
				"UPDATE detection_request_attempts SET error_code = 'blocked' WHERE id = ?",
				ATTEMPT_B
			)).isZero();
			assertThat(update(connection,
				"DELETE FROM detection_signal_results WHERE id = ?", SIGNAL_B
			)).isZero();
			assertThatThrownBy(() -> insertClass(
				connection,
				UUID.randomUUID(),
				TEACHER_B,
				"cross tenant"
			)).isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
			connection.rollback();
			setTeacher(connection, TEACHER_A);
			assertThatThrownBy(() -> insertEvidence(connection, SIGNAL_B))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
			connection.rollback();
		}
	}

	@Test
	void preventsOwnershipTransferAndClearsContextAfterCommitAndRollback()
		throws Exception {
		long backendPid;
		try (Connection connection = restrictedDataSource.getConnection()) {
			backendPid = backendPid(connection);
			setTeacher(connection, TEACHER_A);
			assertThat(update(connection,
				"UPDATE class_groups SET name = 'A updated' WHERE id = ?", CLASS_A
			)).isEqualTo(1);
			assertThatThrownBy(() -> updateTwoIds(
				connection,
				"UPDATE class_groups SET teacher_id = ? WHERE id = ?",
				TEACHER_B,
				CLASS_A
			)).isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
			connection.rollback();
		}

		try (Connection connection = restrictedDataSource.getConnection()) {
			assertThat(backendPid(connection)).isEqualTo(backendPid);
			assertThat(currentTeacherSetting(connection)).isBlank();
			setTeacher(connection, TEACHER_A);
			assertThat(selectById(connection, "class_groups", CLASS_A)).isEqualTo(1);
			connection.commit();
		}

		try (Connection connection = restrictedDataSource.getConnection()) {
			assertThat(backendPid(connection)).isEqualTo(backendPid);
			assertThat(currentTeacherSetting(connection)).isBlank();
			setTeacher(connection, TEACHER_B);
			assertThat(selectById(connection, "class_groups", CLASS_A)).isZero();
			assertThat(selectById(connection, "class_groups", CLASS_B)).isEqualTo(1);
			connection.rollback();
		}

		try (Connection connection = restrictedDataSource.getConnection()) {
			assertThat(backendPid(connection)).isEqualTo(backendPid);
			assertThat(currentTeacherSetting(connection)).isBlank();
			assertThat(count(connection, "class_groups")).isZero();
			connection.rollback();
		}
	}

	private void insertFixtures() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-31T09:00:00+09:00");
		for (UUID student : new UUID[]{STUDENT_A, STUDENT_B}) {
			administrator.update("""
				INSERT INTO student_profiles
				    (id, alias, grade, created_at, updated_at)
				VALUES (?, ?, 1, ?, ?)
				""", student, "student-" + student, now, now);
		}
		administrator.update("""
			INSERT INTO class_groups
			    (id, teacher_id, name, status, created_at, updated_at)
			VALUES (?, ?, 'A class', 'ACTIVE', ?, ?),
			       (?, ?, 'B class', 'ACTIVE', ?, ?)
			""", CLASS_A, TEACHER_A, now, now, CLASS_B, TEACHER_B, now, now);
		administrator.update("""
			INSERT INTO teacher_student_relationships
			    (id, teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, ?, 'ACTIVE', ?, ?),
			       (?, ?, ?, 'ACTIVE', ?, ?)
			""",
			RELATIONSHIP_A, TEACHER_A, STUDENT_A, now, now,
			RELATIONSHIP_B, TEACHER_B, STUDENT_B, now, now);
		insertRun(RUN_A, TEACHER_A, "rls-a:2026-07-31");
		insertRun(RUN_B, TEACHER_B, "rls-b:2026-07-31");
		insertDetectionChildren(RUN_A, ATTEMPT_A, SIGNAL_A, "request-a");
		insertDetectionChildren(RUN_B, ATTEMPT_B, SIGNAL_B, "request-b");
	}

	private void insertRun(UUID runId, UUID teacherId, String idempotencyKey) {
		administrator.update("""
			INSERT INTO detection_runs (
			    id, teacher_id, analysis_date, week_start, idempotency_key,
			    snapshot_hash, snapshot_payload
			)
			VALUES (?, ?, DATE '2026-07-31', DATE '2026-07-27', ?, ?, '{}')
			""",
			runId,
			teacherId,
			idempotencyKey,
			"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
		);
	}

	private void insertDetectionChildren(
		UUID runId,
		UUID attemptId,
		UUID signalId,
		String requestId
	) {
		administrator.update("""
			INSERT INTO detection_request_attempts
			    (id, detection_run_id, request_id, attempt_number, requested_at)
			VALUES (?, ?, ?, 1, TIMESTAMPTZ '2026-07-31 09:01:00+09')
			""", attemptId, runId, requestId);
		administrator.update("""
			INSERT INTO detection_signal_results (
			    id, detection_run_id, external_signal_id, student_ref, class_ref,
			    rule_id, signal_type, display_label, score, rank, lifecycle,
			    brief_text, gate_passed, fallback_used
			)
			VALUES (?, ?, ?, 'student', 'class', 'R1', 'risk', 'Risk',
			        0.5, 1, 'NEW', 'brief', true, false)
			""", signalId, runId, "external-" + signalId);
		administrator.update("""
			INSERT INTO detection_result_evidence
			    (id, detection_signal_result_id, source_hint, record_id, summary)
			VALUES (?, ?, 'learning_event', ?, 'evidence')
			""", UUID.randomUUID(), signalId, "record-" + signalId);
	}

	private void setTeacher(Connection connection, UUID teacherId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT set_config('checkon.current_teacher_id', ?, true)"
		)) {
			statement.setString(1, teacherId.toString());
			statement.executeQuery();
		}
	}

	private String currentTeacherSetting(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery(
				 "SELECT current_setting('checkon.current_teacher_id', true)"
			 )) {
			result.next();
			String setting = result.getString(1);
			return setting == null ? "" : setting;
		}
	}

	private int count(Connection connection, String table) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
			result.next();
			return result.getInt(1);
		}
	}

	private java.util.List<UUID> ids(Connection connection, String table)
		throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery(
				 "SELECT id FROM " + table + " ORDER BY id"
			 )) {
			java.util.List<UUID> ids = new java.util.ArrayList<>();
			while (result.next()) {
				ids.add(result.getObject(1, UUID.class));
			}
			return ids;
		}
	}

	private int selectById(Connection connection, String table, UUID id)
		throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT count(*) FROM " + table + " WHERE id = ?"
		)) {
			statement.setObject(1, id);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		}
	}

	private int update(Connection connection, String sql, UUID id)
		throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id);
			return statement.executeUpdate();
		}
	}

	private int updateTwoIds(Connection connection, String sql, UUID first, UUID second)
		throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, first);
			statement.setObject(2, second);
			return statement.executeUpdate();
		}
	}

	private void insertClass(
		Connection connection,
		UUID id,
		UUID teacherId,
		String name
	) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO class_groups
			    (id, teacher_id, name, status, created_at, updated_at)
			VALUES (?, ?, ?, 'ACTIVE', now(), now())
			""")) {
			statement.setObject(1, id);
			statement.setObject(2, teacherId);
			statement.setString(3, name);
			statement.executeUpdate();
		}
	}

	private void insertEvidence(Connection connection, UUID signalId)
		throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO detection_result_evidence
			    (id, detection_signal_result_id, source_hint, record_id, summary)
			VALUES (?, ?, 'learning_event', ?, 'cross tenant')
			""")) {
			UUID evidenceId = UUID.randomUUID();
			statement.setObject(1, evidenceId);
			statement.setObject(2, signalId);
			statement.setString(3, "record-" + evidenceId);
			statement.executeUpdate();
		}
	}

	private long backendPid(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
			result.next();
			return result.getLong(1);
		}
	}

	private int queryInt(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}
}
