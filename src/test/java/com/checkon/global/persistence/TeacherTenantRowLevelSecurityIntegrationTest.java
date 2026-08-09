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
	private static final UUID STUDENT_A_WITHOUT_PII =
		UUID.fromString("019846dc-7c00-7000-8000-000000001013");
	private static final UUID STUDENT_B_WITHOUT_PII =
		UUID.fromString("019846dc-7c00-7000-8000-000000001014");
	private static final UUID CLASS_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001021");
	private static final UUID CLASS_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001022");
	private static final UUID RELATIONSHIP_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001031");
	private static final UUID RELATIONSHIP_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001032");
	private static final UUID ENROLLMENT_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001035");
	private static final UUID ENROLLMENT_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001036");
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
	private static final UUID LEARNING_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001071");
	private static final UUID LEARNING_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001072");
	private static final UUID AI_ALIAS_A =
		UUID.fromString("019846dc-7c00-7000-8000-000000001081");
	private static final UUID AI_ALIAS_B =
		UUID.fromString("019846dc-7c00-7000-8000-000000001082");
	private static final UUID ALERT_A = UUID.fromString("019846dc-7c00-7000-8000-000000001091");
	private static final UUID ALERT_B = UUID.fromString("019846dc-7c00-7000-8000-000000001092");
	private static final UUID INTERVENTION_A = UUID.fromString("019846dc-7c00-7000-8000-0000000010a1");
	private static final UUID INTERVENTION_B = UUID.fromString("019846dc-7c00-7000-8000-0000000010a2");
	private static final UUID REMINDER_A = UUID.fromString("019846dc-7c00-7000-8000-0000000010b1");
	private static final UUID REMINDER_B = UUID.fromString("019846dc-7c00-7000-8000-0000000010b2");
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
		administrator.execute("GRANT SELECT ON teacher_profiles TO " + RESTRICTED_ROLE);
		administrator.execute("""
			GRANT SELECT, INSERT, UPDATE, DELETE
			ON class_groups,
			   teacher_student_relationships,
			   class_enrollments,
			   detection_runs,
			   detection_request_attempts,
			   detection_signal_results,
			   detection_result_evidence,
			   learning_records,
			   ai_student_aliases,
			   student_personal_information,
			   engagement_alerts,
			   interventions,
			   intervention_reminders
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
				    'detection_result_evidence',
				    'learning_records',
				    'ai_student_aliases',
				    'student_personal_information',
				    'engagement_alerts','interventions','intervention_reminders'
				  )
				  AND table_metadata.relrowsecurity
				  AND table_metadata.relforcerowsecurity
				""")).isEqualTo(13);
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
				    'detection_result_evidence',
				    'learning_records',
				    'ai_student_aliases',
				    'student_personal_information',
				    'engagement_alerts','interventions','intervention_reminders'
				  )
				""")).isEqualTo(52);
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
			assertThat(count(connection, "class_enrollments")).isZero();
			assertThat(count(connection, "detection_request_attempts")).isZero();
			assertThat(count(connection, "learning_records")).isZero();
			assertThat(count(connection, "ai_student_aliases")).isZero();
			assertThat(count(connection, "student_personal_information")).isZero();
			assertThat(count(connection, "engagement_alerts")).isZero();
			assertThat(update(connection,
				"UPDATE class_groups SET name = 'blocked' WHERE id = ?", CLASS_A
			)).isZero();
			assertThat(update(connection,
				"UPDATE class_enrollments SET enrolled_at = now() WHERE id = ?",
				ENROLLMENT_A
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
			assertThat(ids(connection, "class_enrollments")).containsExactly(ENROLLMENT_A);
			assertThat(ids(connection, "teacher_student_relationships"))
				.hasSize(2).contains(RELATIONSHIP_A);
			assertThat(ids(connection, "detection_runs")).containsExactly(RUN_A);
			assertThat(ids(connection, "detection_request_attempts"))
				.containsExactly(ATTEMPT_A);
			assertThat(ids(connection, "detection_signal_results"))
				.containsExactly(SIGNAL_A);
			assertThat(ids(connection, "learning_records")).containsExactly(LEARNING_A);
			assertThat(ids(connection, "ai_student_aliases")).containsExactly(AI_ALIAS_A);
			assertThat(studentPersonalInformationCount(connection)).isEqualTo(1);
			assertThat(ids(connection, "engagement_alerts")).containsExactly(ALERT_A);
			assertThat(calendarAlertCount(
				connection, TEACHER_A, "2026-07-28", "2026-08-03"
			)).isEqualTo(1);
			assertThat(calendarAlertCount(
				connection, TEACHER_B, "2026-07-28", "2026-08-03"
			)).isZero();
			assertThat(ids(connection, "interventions")).containsExactly(INTERVENTION_A);
			assertThat(ids(connection, "intervention_reminders")).containsExactly(REMINDER_A);
			assertThat(count(connection, "detection_result_evidence")).isEqualTo(1);
			UUID ownClass = UUID.randomUUID();
			insertClass(connection, ownClass, TEACHER_A, "allowed own class");
			assertThat(selectById(connection, "class_groups", ownClass)).isEqualTo(1);
			assertThat(update(connection,
				"DELETE FROM class_groups WHERE id = ?", ownClass
			)).isZero();
			assertThat(selectById(connection, "class_groups", ownClass)).isEqualTo(1);
			assertThat(selectById(connection, "class_groups", CLASS_B)).isZero();
			assertThat(update(connection,
				"UPDATE class_groups SET name = 'blocked' WHERE id = ?", CLASS_B
			)).isZero();
			assertThat(update(connection,
				"UPDATE class_enrollments SET enrolled_at = now() WHERE id = ?",
				ENROLLMENT_B
			)).isZero();
			assertThat(update(connection,
				"UPDATE class_groups SET subject = 'Science', memo = 'owned' WHERE id = ?",
				CLASS_A
			)).isEqualTo(1);
			insertEnrollment(
				connection,
				UUID.randomUUID(),
				CLASS_A,
				TEACHER_A,
				STUDENT_A_WITHOUT_PII
			);
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
			assertThat(update(connection,
				"UPDATE learning_records SET source_type = 'blocked' WHERE id = ?", LEARNING_B
			)).isZero();
			assertThat(update(connection,
				"DELETE FROM ai_student_aliases WHERE id = ?", AI_ALIAS_B
			)).isZero();
			assertThat(update(connection,
				"UPDATE student_personal_information SET real_name = 'blocked' WHERE student_id = ?",
				STUDENT_B
			)).isZero();
			assertThat(insertPersonalInformation(
				connection, STUDENT_A_WITHOUT_PII, TEACHER_A, "허용 학생"
			)).isEqualTo(1);
			assertThat(update(connection,
				"UPDATE student_personal_information SET real_name = '수정 학생' WHERE student_id = ?",
				STUDENT_A_WITHOUT_PII
			)).isEqualTo(1);
			assertThat(update(connection,
				"DELETE FROM student_personal_information WHERE student_id = ?",
				STUDENT_A_WITHOUT_PII
			)).isEqualTo(1);
			assertThat(update(connection,"UPDATE engagement_alerts SET updated_at=now() WHERE id=?",ALERT_B)).isZero();
			assertThat(update(connection,"DELETE FROM interventions WHERE id=?",INTERVENTION_B)).isZero();
			assertThat(update(connection,"UPDATE intervention_reminders SET scheduled_at=now() WHERE id=?",REMINDER_B)).isZero();
			insertLearningRecord(connection, UUID.randomUUID(), TEACHER_A, STUDENT_A);
			assertThatThrownBy(() -> insertLearningRecord(
				connection, UUID.randomUUID(), TEACHER_B, STUDENT_B))
				.isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
			connection.rollback();
			setTeacher(connection, TEACHER_A);
			assertThatThrownBy(() -> insertPersonalInformation(
				connection, STUDENT_B_WITHOUT_PII, TEACHER_A, "차단 학생"
			)).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
			connection.rollback();
			setTeacher(connection, TEACHER_A);
			assertThatThrownBy(() -> insertEndedEnrollment(
				connection,
				UUID.randomUUID(),
				CLASS_B,
				TEACHER_B,
				STUDENT_B_WITHOUT_PII
			)).isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
			connection.rollback();
			setTeacher(connection, TEACHER_A);
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
		for (UUID student : new UUID[]{
			STUDENT_A, STUDENT_B, STUDENT_A_WITHOUT_PII, STUDENT_B_WITHOUT_PII
		}) {
			administrator.update("""
				INSERT INTO student_profiles
				    (id, alias, grade, created_at, updated_at)
				VALUES (?, ?, 1, ?, ?)
				""", student, "student-" + student, now, now);
		}
		administrator.update("""
			INSERT INTO class_groups
			    (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, 'A class', 'Math', 'ACTIVE', ?, ?),
			       (?, ?, 'B class', 'Math', 'ACTIVE', ?, ?)
			""", CLASS_A, TEACHER_A, now, now, CLASS_B, TEACHER_B, now, now);
		administrator.update("""
			INSERT INTO teacher_student_relationships
			    (id, teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, ?, 'ACTIVE', ?, ?),
			       (?, ?, ?, 'ACTIVE', ?, ?),
			       (uuidv7(), ?, ?, 'ACTIVE', ?, ?),
			       (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""",
			RELATIONSHIP_A, TEACHER_A, STUDENT_A, now, now,
			RELATIONSHIP_B, TEACHER_B, STUDENT_B, now, now,
			TEACHER_A, STUDENT_A_WITHOUT_PII, now, now,
			TEACHER_B, STUDENT_B_WITHOUT_PII, now, now);
		administrator.update("""
			INSERT INTO class_enrollments (
			    id, class_group_id, teacher_id, student_id, status,
			    enrolled_at, created_at
			) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?),
			         (?, ?, ?, ?, 'ACTIVE', ?, ?)
			""",
			ENROLLMENT_A, CLASS_A, TEACHER_A, STUDENT_A, now, now,
			ENROLLMENT_B, CLASS_B, TEACHER_B, STUDENT_B, now, now);
		administrator.update("""
			INSERT INTO student_personal_information
			    (student_id, real_name, updated_by_account_id, updated_by_role,
			     created_at, updated_at)
			SELECT ?, '학생 A', account_id, 'TEACHER', ?, ?
			FROM teacher_profiles WHERE id = ?
			UNION ALL
			SELECT ?, '학생 B', account_id, 'TEACHER', ?, ?
			FROM teacher_profiles WHERE id = ?
			""", STUDENT_A, now, now, TEACHER_A, STUDENT_B, now, now, TEACHER_B);
		insertRun(RUN_A, TEACHER_A, "rls-a:2026-07-31");
		insertRun(RUN_B, TEACHER_B, "rls-b:2026-07-31");
		insertDetectionChildren(RUN_A, ATTEMPT_A, SIGNAL_A, "request-a",
			"st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		insertDetectionChildren(RUN_B, ATTEMPT_B, SIGNAL_B, "request-b",
			"st_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		insertLearningFixture(LEARNING_A, AI_ALIAS_A, TEACHER_A, STUDENT_A,
			"st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		insertLearningFixture(LEARNING_B, AI_ALIAS_B, TEACHER_B, STUDENT_B,
			"st_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		insertEngagementFixture(ALERT_A, INTERVENTION_A, REMINDER_A, TEACHER_A, STUDENT_A, SIGNAL_A);
		insertEngagementFixture(ALERT_B, INTERVENTION_B, REMINDER_B, TEACHER_B, STUDENT_B, SIGNAL_B);
	}

	private void insertEngagementFixture(UUID alert, UUID intervention, UUID reminder,
		UUID teacher, UUID student, UUID signal) {
		administrator.update("INSERT INTO engagement_alerts(id,teacher_id,student_id,detection_signal_result_id,status,decided_at,created_at,updated_at) VALUES(?,?,?,?,'APPROVED',now(),now(),now())", alert,teacher,student,signal);
		administrator.update("INSERT INTO interventions(id,teacher_id,student_id,alert_id,type,content,status,created_at,updated_at) VALUES(?,?,?,?, 'CALL','fixture','OPEN',now(),now())", intervention,teacher,student,alert);
		administrator.update("INSERT INTO intervention_reminders(id,teacher_id,intervention_id,scheduled_at,status,created_at,updated_at) VALUES(?,?,?,now(),'ACTIVE',now(),now())", reminder,teacher,intervention);
	}

	private void insertLearningFixture(UUID recordId, UUID aliasId, UUID teacherId,
		UUID studentId, String alias) {
		administrator.update("""
			INSERT INTO learning_records
			(id, teacher_id, student_id, record_type, occurred_at, source_type,
			 created_at, updated_at)
			VALUES (?, ?, ?, 'SOLVE', now(), 'fixture', now(), now())
			""", recordId, teacherId, studentId);
		administrator.update("""
			INSERT INTO ai_student_aliases (id, teacher_id, student_id, alias, created_at)
			VALUES (?, ?, ?, ?, now())
			""", aliasId, teacherId, studentId, alias);
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
		String requestId,
		String studentRef
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
			VALUES (?, ?, ?, ?, 'class', 'R1', 'risk', 'Risk',
			        0.5, 1, 'NEW', 'brief', true, false)
			""", signalId, runId, "external-" + signalId, studentRef);
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
			    (id, teacher_id, name, subject, status, created_at, updated_at)
			VALUES (?, ?, ?, 'Math', 'ACTIVE', now(), now())
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

	private void insertEnrollment(
		Connection connection,
		UUID id,
		UUID classId,
		UUID teacherId,
		UUID studentId
	) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO class_enrollments (
			    id, class_group_id, teacher_id, student_id, status,
			    enrolled_at, created_at
			) VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
			""")) {
			statement.setObject(1, id);
			statement.setObject(2, classId);
			statement.setObject(3, teacherId);
			statement.setObject(4, studentId);
			statement.executeUpdate();
		}
	}

	private void insertEndedEnrollment(
		Connection connection,
		UUID id,
		UUID classId,
		UUID teacherId,
		UUID studentId
	) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO class_enrollments (
			    id, class_group_id, teacher_id, student_id, status,
			    enrolled_at, ended_at, created_at
			) VALUES (?, ?, ?, ?, 'ENDED', now(), now(), now())
			""")) {
			statement.setObject(1, id);
			statement.setObject(2, classId);
			statement.setObject(3, teacherId);
			statement.setObject(4, studentId);
			statement.executeUpdate();
		}
	}

	private void insertLearningRecord(Connection connection, UUID id,
		UUID teacherId, UUID studentId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO learning_records
			(id, teacher_id, student_id, record_type, occurred_at, source_type,
			 created_at, updated_at)
			VALUES (?, ?, ?, 'SUBMIT', now(), 'rls-test', now(), now())
			""")) {
			statement.setObject(1, id);
			statement.setObject(2, teacherId);
			statement.setObject(3, studentId);
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

	private int studentPersonalInformationCount(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery(
				 "SELECT count(*) FROM student_personal_information"
			 )) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private int insertPersonalInformation(
		Connection connection, UUID studentId, UUID teacherId, String realName
	) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO student_personal_information(
			    student_id, real_name, updated_by_account_id, updated_by_role,
			    created_at, updated_at
			)
			SELECT ?, ?, account_id, 'TEACHER', now(), now()
			FROM teacher_profiles WHERE id = ?
			""")) {
			statement.setObject(1, studentId);
			statement.setString(2, realName);
			statement.setObject(3, teacherId);
			return statement.executeUpdate();
		}
	}

	private int calendarAlertCount(
		Connection connection,
		UUID teacherId,
		String startedAt,
		String endedAt
	) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT count(alert.id)
			FROM engagement_alerts alert
			JOIN detection_signal_results signal
			  ON signal.id = alert.detection_signal_result_id
			JOIN detection_runs run ON run.id = signal.detection_run_id
			WHERE alert.teacher_id = ?
			  AND run.teacher_id = ?
			  AND run.analysis_date BETWEEN ?::date AND ?::date
			""")) {
			statement.setObject(1, teacherId);
			statement.setObject(2, teacherId);
			statement.setString(3, startedAt);
			statement.setString(4, endedAt);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		}
	}
}
