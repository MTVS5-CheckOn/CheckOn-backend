package com.checkon.problem.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import com.checkon.support.RosterTestFixture;

@SpringBootTest
@Testcontainers
@Transactional
@DisplayName("문제 출제 child execution 스키마")
class ProblemGenerationExecutionSchemaIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER = UUID.fromString("0198fa00-0000-7000-8000-000000000001");
	private static final UUID STUDENT = UUID.fromString("0198fa00-0000-7000-8000-000000000002");
	private static final UUID REQUEST = UUID.fromString("0198fa00-0000-7000-8000-000000000003");
	private static final UUID TARGET = UUID.fromString("0198fa00-0000-7000-8000-000000000004");
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		jdbc.update("INSERT INTO student_profiles(id,alias,grade,created_at,updated_at) VALUES (?, 'child 학생', 1, ?, ?)",
			STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships(
			    id, teacher_id, student_id, status, started_at, created_at
			) VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO problem_generation_requests(
			    id, teacher_id, tenant_alias, target_kind, student_id, target_ref,
			    ai_idempotency_key, snapshot_hash, request_payload, status,
			    requested_at, updated_at
			) VALUES (?, ?, 'tn_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'STUDENT', ?,
			    'st_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'pg_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
			    'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
			    '{}'::jsonb, 'QUEUED', ?, ?)
			""", REQUEST, TEACHER, STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO problem_generation_request_targets(
			    id, teacher_id, problem_request_id, ordinal, area_tag, type_tag, requested_count
			) VALUES (?, ?, ?, 1, 'language', 'FACT', 3)
			""", TARGET, TEACHER, REQUEST);
	}

	@Test
	@DisplayName("Given 부모 요청과 target이 있을 때 When child를 저장하면 Then target별 식별자와 snapshot을 보존한다")
	void storesOneChildPerTarget() {
		insertExecution(UUID.randomUUID(), "QUEUED", null);

		var stored = jdbc.queryForMap("""
			SELECT problem_request_id, request_target_id, target_index, status,
			       ai_idempotency_key, request_snapshot_hash
			FROM problem_generation_executions WHERE problem_request_id = ?
			""", REQUEST);

		assertThat(stored.get("problem_request_id")).isEqualTo(REQUEST);
		assertThat(stored.get("request_target_id")).isEqualTo(TARGET);
		assertThat(stored.get("target_index")).isEqualTo(0);
		assertThat(stored.get("status")).isEqualTo("QUEUED");
		assertThat(stored.get("ai_idempotency_key")).isEqualTo("child-key-0");
		assertThat(stored.get("request_snapshot_hash"))
			.isEqualTo("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
	}

	@Test
	@DisplayName("Given target에 child가 이미 있을 때 When 같은 target child를 추가하면 Then DB가 중복을 거절한다")
	void rejectsDuplicateChildForTarget() {
		insertExecution(UUID.randomUUID(), "QUEUED", null);

		assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), "QUEUED", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Given child가 종단 상태일 때 When 완료 시각 없이 저장하면 Then DB가 불완전 상태를 거절한다")
	void rejectsTerminalChildWithoutCompletionTime() {
		assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), "TIMED_OUT", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Given 일부 child만 성공했을 때 When 부모를 갱신하면 Then PARTIAL_SUCCESS를 저장할 수 있다")
	void acceptsPartialSuccessParentStatus() {
		assertThat(jdbc.update(
			"UPDATE problem_generation_requests SET status = 'PARTIAL_SUCCESS', completed_at = ?, updated_at = ? WHERE id = ?",
			time(), time(), REQUEST
		)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM problem_generation_requests WHERE id = ?", String.class, REQUEST
		)).isEqualTo("PARTIAL_SUCCESS");
	}

	private void insertExecution(UUID id, String status, Instant completedAt) {
		jdbc.update("""
			INSERT INTO problem_generation_executions(
			    id, teacher_id, problem_request_id, request_target_id, target_index,
			    status, ai_idempotency_key, request_snapshot_hash, request_snapshot,
			    created_at, completed_at, updated_at
			) VALUES (?, ?, ?, ?, 0, ?, 'child-key-0',
			    'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
			    '{"area_tag":"language","type_tag":"fact","count":3}'::jsonb,
			    ?, ?, ?)
			""", id, TEACHER, REQUEST, TARGET, status, time(),
			completedAt == null ? null : completedAt.atOffset(ZoneOffset.UTC), time());
	}

	private static Object time() {
		return NOW.atOffset(ZoneOffset.UTC);
	}
}
