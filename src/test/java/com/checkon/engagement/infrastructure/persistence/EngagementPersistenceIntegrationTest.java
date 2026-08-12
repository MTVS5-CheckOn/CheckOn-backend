package com.checkon.engagement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.support.RosterTestFixture;
import com.checkon.engagement.application.EngagementCandidateService;

@SpringBootTest
@Testcontainers
class EngagementPersistenceIntegrationTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString(
		"0198e100-0000-7000-8000-000000000001"
	);
	private static final UUID OTHER_TEACHER = UUID.fromString(
		"0198e100-0000-7000-8000-000000000002"
	);
	private static final UUID STUDENT = UUID.fromString(
		"0198e100-0000-7000-8000-000000000003"
	);
	private static final UUID SIGNAL_WITH_EVIDENCE = UUID.fromString(
		"0198e100-0000-7000-8000-000000000004"
	);
	private static final UUID SIGNAL_WITHOUT_EVIDENCE = UUID.fromString(
		"0198e100-0000-7000-8000-000000000005"
	);
	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	EngagementCandidateService engagementCandidateService;

	UUID approvedAlert;
	UUID intervention;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM intervention_reminders");
		jdbc.update("DELETE FROM interventions");
		jdbc.update("DELETE FROM alert_follow_up_todos");
		jdbc.update("DELETE FROM engagement_alerts");
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM student_profiles");

		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		RosterTestFixture.insertTeacher(jdbc, OTHER_TEACHER);
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'engagement-student', 1, ?, ?)
			""", STUDENT, offset(NOW), offset(NOW));
		jdbc.update("""
			INSERT INTO ai_student_aliases (teacher_id, student_id, alias, created_at)
			VALUES (?, ?, 'st_cccccccccccccccccccccccccccccccc', ?)
			""", TEACHER, STUDENT, offset(NOW));

		UUID run = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO detection_runs (
			    id, teacher_id, analysis_date, week_start, idempotency_key,
			    snapshot_hash, snapshot_payload, created_at, updated_at
			)
			VALUES (?, ?, DATE '2026-08-04', DATE '2026-08-03', 'engagement-db',
			        'sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
			        '{}', ?, ?)
			""", run, TEACHER, offset(NOW), offset(NOW));
		insertSignal(run, SIGNAL_WITH_EVIDENCE, "with-evidence");
		insertSignal(run, SIGNAL_WITHOUT_EVIDENCE, "without-evidence");
		jdbc.update("""
			INSERT INTO detection_result_evidence
			    (detection_signal_result_id, source_hint, record_id, summary)
			VALUES (?, 'learning_records', 'record-1', 'verified evidence')
			""", SIGNAL_WITH_EVIDENCE);

		approvedAlert = jdbc.queryForObject("""
			INSERT INTO engagement_alerts (
			    teacher_id, student_id, detection_signal_result_id, status,
			    decided_at, created_at, updated_at
			)
			VALUES (?, ?, ?, 'APPROVED', ?, ?, ?)
			RETURNING id
			""", UUID.class, TEACHER, STUDENT, SIGNAL_WITH_EVIDENCE,
			offset(NOW), offset(NOW), offset(NOW));
		intervention = jdbc.queryForObject("""
			INSERT INTO interventions (
			    teacher_id, student_id, alert_id, type, content, status,
			    created_at, updated_at
			)
			VALUES (?, ?, ?, 'CALL', '상담', 'OPEN', ?, ?)
			RETURNING id
			""", UUID.class, TEACHER, STUDENT, approvedAlert, offset(NOW), offset(NOW));
	}

	@Test
	void alertRequiresTenantOwnedDetectionEvidence() {
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO engagement_alerts (
			    teacher_id, student_id, detection_signal_result_id,
			    status, created_at, updated_at
			)
			VALUES (?, ?, ?, 'PENDING_REVIEW', ?, ?)
			""", TEACHER, STUDENT, SIGNAL_WITHOUT_EVIDENCE, offset(NOW), offset(NOW)))
			.isInstanceOf(DataAccessException.class);

		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO engagement_alerts (
			    teacher_id, student_id, detection_signal_result_id,
			    status, created_at, updated_at
			)
			VALUES (?, ?, ?, 'PENDING_REVIEW', ?, ?)
			""", OTHER_TEACHER, STUDENT, SIGNAL_WITH_EVIDENCE, offset(NOW), offset(NOW)))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	void advisorySignalIsRetainedButDoesNotCreateAlertOrTodo() {
		UUID advisorySignal = UUID.fromString("0198e100-0000-7000-8000-000000000006");
		UUID run = jdbc.queryForObject(
			"SELECT id FROM detection_runs WHERE idempotency_key = 'engagement-db'", UUID.class
		);
		jdbc.update("""
			INSERT INTO detection_signal_results (
			    id, detection_run_id, external_signal_id, student_ref, class_ref,
			    rule_id, signal_type, display_label, score, rank, advisory, lifecycle,
			    brief_text, gate_passed, fallback_used, created_at
			)
			VALUES (?, ?, 'advisory-signal', 'st_cccccccccccccccccccccccccccccccc',
			        'class', 'R6', 'advisory', '참고 신호', 0.5, 2, true, 'NEW',
			        '학생 상세 참고용', true, false, ?)
			""", advisorySignal, run, offset(NOW));
		jdbc.update("""
			INSERT INTO detection_result_evidence
			    (detection_signal_result_id, source_hint, record_id, summary)
			VALUES (?, 'learning_event', 'advisory-record', '참고 근거')
			""", advisorySignal);

		engagementCandidateService.createPendingAlerts(TEACHER, run, NOW);

		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM engagement_alerts
			WHERE detection_signal_result_id = ?
			""", Integer.class, advisorySignal)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_follow_up_todos", Integer.class))
			.isZero();
	}

	@Test
	void interventionRequiresApprovedAlertWithSameTeacherAndStudent() {
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO interventions (
			    teacher_id, student_id, alert_id, type, content, status,
			    created_at, updated_at
			)
			VALUES (?, ?, ?, 'CALL', 'invalid', 'OPEN', ?, ?)
			""", OTHER_TEACHER, STUDENT, approvedAlert, offset(NOW), offset(NOW)))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	void completedOrCancelledReminderAllowsNewActiveReminder() {
		UUID first = insertReminder();
		assertThatThrownBy(this::insertReminder)
			.isInstanceOf(DataIntegrityViolationException.class);

		jdbc.update("""
			UPDATE intervention_reminders
			SET status = 'COMPLETED', finished_at = ?, updated_at = ?
			WHERE id = ?
			""", offset(NOW.plusSeconds(1)), offset(NOW.plusSeconds(1)), first);
		UUID second = insertReminder();
		jdbc.update("""
			UPDATE intervention_reminders
			SET status = 'CANCELLED', finished_at = ?, updated_at = ?
			WHERE id = ?
			""", offset(NOW.plusSeconds(2)), offset(NOW.plusSeconds(2)), second);

		assertThat(insertReminder()).isNotNull();
	}

	@Test
	void concurrentRequestsCannotCreateTwoActiveReminders() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> concurrentInsert(ready, start));
			Future<Boolean> second = executor.submit(() -> concurrentInsert(ready, start));
			ready.await();
			start.countDown();

			assertThat(java.util.List.of(first.get(), second.get()))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM intervention_reminders
			WHERE intervention_id = ? AND status = 'ACTIVE'
			""", Integer.class, intervention)).isOne();
	}

	private boolean concurrentInsert(CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		start.await();
		try (Connection connection = dataSource.getConnection();
			 PreparedStatement statement = connection.prepareStatement("""
				 INSERT INTO intervention_reminders (
				     teacher_id, intervention_id, scheduled_at, status,
				     created_at, updated_at
				 ) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
				 """)) {
			statement.setObject(1, TEACHER);
			statement.setObject(2, intervention);
			statement.setObject(3, offset(NOW.plusSeconds(60)));
			statement.setObject(4, offset(NOW));
			statement.setObject(5, offset(NOW));
			statement.executeUpdate();
			return true;
		}
		catch (Exception exception) {
			return false;
		}
	}

	private UUID insertReminder() {
		return jdbc.queryForObject("""
			INSERT INTO intervention_reminders (
			    teacher_id, intervention_id, scheduled_at, status,
			    created_at, updated_at
			)
			VALUES (?, ?, ?, 'ACTIVE', ?, ?)
			RETURNING id
			""", UUID.class, TEACHER, intervention, offset(NOW.plusSeconds(60)),
			offset(NOW), offset(NOW));
	}

	private void insertSignal(UUID run, UUID signal, String externalId) {
		jdbc.update("""
			INSERT INTO detection_signal_results (
			    id, detection_run_id, external_signal_id, student_ref, class_ref,
			    rule_id, signal_type, display_label, score, rank, lifecycle,
			    brief_text, gate_passed, fallback_used, created_at
			)
			VALUES (?, ?, ?, 'st_cccccccccccccccccccccccccccccccc',
			        'class', 'R1', 'RISK', '위험', 0.8,
			        1, 'NEW', 'summary', true, false, ?)
			""", signal, run, externalId, offset(NOW));
	}

	private static java.time.OffsetDateTime offset(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
