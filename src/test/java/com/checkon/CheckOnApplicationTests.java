package com.checkon;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@Transactional
class CheckOnApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");
	private final JdbcTemplate jdbcTemplate;

	@Autowired
	CheckOnApplicationTests(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Test
	void contextLoads() {
	}

	@Test
	void postgresqlSupportsUuidV7() {
		Boolean isVersion7 = jdbcTemplate.queryForObject(
			"SELECT uuid_extract_version(uuidv7()) = 7",
			Boolean.class
		);

		assertTrue(Boolean.TRUE.equals(isVersion7));
	}

	@Test
	void postgresqlInstantToTimestamptz() {
		Instant expected = Instant.parse("2026-07-27T12:34:56Z");

		Instant actual = jdbcTemplate.queryForObject(
			"SELECT CAST(? AS timestamptz)",
			(resultSet, rowNumber) ->
				resultSet.getObject(1, OffsetDateTime.class).toInstant(),
			expected.atOffset(ZoneOffset.UTC)
		);

		assertThat(actual).isEqualTo(expected);
	}

	@Test
	void flywayCreatesCurrentApplicationTables() {
		String detectionRuns = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.detection_runs')::text",
			String.class
		);
		String detectionAttempts = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.detection_request_attempts')::text",
			String.class
		);
		String rosterRelationships = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.teacher_student_relationships')::text",
			String.class
		);
		String alertFollowUpTodos = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.alert_follow_up_todos')::text",
			String.class
		);

		assertThat(detectionRuns).isEqualTo("detection_runs");
		assertThat(detectionAttempts).isEqualTo("detection_request_attempts");
		assertThat(rosterRelationships).isEqualTo("teacher_student_relationships");
		assertThat(alertFollowUpTodos).isEqualTo("alert_follow_up_todos");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
			String.class
		)).isEqualTo("10");
	}

	@Test
	void rejectsSecondDetectionRunForSameTeacherAndAnalysisDate() {
		String insertSql = """
			INSERT INTO detection_runs (
			    id,
			    teacher_id,
			    analysis_date,
			    week_start,
			    idempotency_key,
			    snapshot_hash,
			    snapshot_payload
			)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""";
		var teacherId = UUID.randomUUID();
		RosterTestFixture.insertTeacher(jdbcTemplate, teacherId);
		var analysisDate = LocalDate.of(2026, 7, 28);
		var weekStart = LocalDate.of(2026, 7, 20);
		var snapshotHash =
			"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

		jdbcTemplate.update(
			insertSql,
			UUID.randomUUID(),
			teacherId,
			analysisDate,
			weekStart,
			"tn_demo_teacher:2026-07-28",
			snapshotHash,
			"{\"snapshot_meta\":{}}"
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			insertSql,
			UUID.randomUUID(),
			teacherId,
			analysisDate,
			weekStart,
			"another-key",
			snapshotHash,
			"{\"snapshot_meta\":{}}"
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsFailedAttemptWithoutErrorCode() {
		var runId = UUID.randomUUID();
		var teacherId = UUID.randomUUID();
		RosterTestFixture.insertTeacher(jdbcTemplate, teacherId);
		jdbcTemplate.update(
			"""
				INSERT INTO detection_runs (
				    id,
				    teacher_id,
				    analysis_date,
				    week_start,
				    idempotency_key,
				    snapshot_hash,
				    snapshot_payload
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
			runId,
			teacherId,
			LocalDate.of(2026, 7, 28),
			LocalDate.of(2026, 7, 20),
			"tn_demo_teacher:2026-07-28",
			"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			"{\"snapshot_meta\":{}}"
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"""
				INSERT INTO detection_request_attempts (
				    detection_run_id,
				    request_id,
				    attempt_number,
				    status,
				    requested_at,
				    completed_at,
				    http_status
				)
				VALUES (?, ?, ?, 'FAILED', ?, ?, 504)
				""",
			runId,
			"request-1",
			1,
			java.time.OffsetDateTime.parse("2026-07-28T02:10:00+09:00"),
			java.time.OffsetDateTime.parse("2026-07-28T02:10:30+09:00")
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
