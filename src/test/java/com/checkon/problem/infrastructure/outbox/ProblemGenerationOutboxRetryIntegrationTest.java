package com.checkon.problem.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.problem.application.CreateProblemGenerationCommand;
import com.checkon.problem.application.ProblemGenerationRequestService;
import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTargetKind;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.support.RosterTestFixture;

@SpringBootTest(properties = "checkon.ai.problem-generation.kafka.outbox-max-attempts=2")
@Testcontainers
@DisplayName("문제 출제 Outbox 재시도")
class ProblemGenerationOutboxRetryIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198fc00-0000-7000-8000-000000000001");
	private static final UUID STUDENT =
		UUID.fromString("0198fc00-0000-7000-8000-000000000011");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-11T00:00:00Z");

	@Autowired JdbcTemplate jdbc;
	@Autowired ProblemGenerationRequestService requestService;
	@Autowired ProblemGenerationOutboxCoordinator coordinator;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM problem_generation_consumed_events");
		jdbc.update("DELETE FROM problem_generation_outbox");
		jdbc.update("DELETE FROM problem_generation_requests");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'retry-student', 1, ?, ?)
			""", STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, time(), time());
	}

	@Test
	@DisplayName("Given Kafka 발행이 계속 실패할 때 When 최대 횟수까지 재시도하면 Then Outbox와 요청을 최종 실패로 남긴다")
	void marksTheOutboxDeadAndTheRequestDeliveryFailed() {
		UUID requestId = requestService.create(TEACHER, new CreateProblemGenerationCommand(
			ProblemTargetKind.STUDENT,
			STUDENT,
			List.of("skill.grammar.001"),
			"2026.08",
			List.of(ProblemTypeTag.CONCEPT),
			2,
			ProblemDifficulty.MEDIUM,
			"outbox-retry-key-0001"
		)).requestId();

		var firstAttempt = coordinator.claim(TEACHER).getFirst();
		coordinator.failed(firstAttempt, new IllegalStateException("broker unavailable"));

		assertThat(jdbc.queryForObject(
			"SELECT status FROM problem_generation_outbox WHERE problem_request_id = ?",
			String.class,
			requestId
		)).isEqualTo("PENDING");
		jdbc.update(
			"UPDATE problem_generation_outbox SET next_attempt_at = now() - interval '1 second' WHERE problem_request_id = ?",
			requestId
		);
		var secondAttempt = coordinator.claim(TEACHER).getFirst();
		coordinator.failed(secondAttempt, new IllegalStateException("broker still unavailable"));

		var outbox = jdbc.queryForMap("""
			SELECT status, attempt_count, last_error
			FROM problem_generation_outbox WHERE problem_request_id = ?
			""", requestId);
		assertThat(outbox.get("status")).isEqualTo("DEAD");
		assertThat(outbox.get("attempt_count")).isEqualTo(2);
		assertThat((String) outbox.get("last_error")).contains("broker still unavailable");
		assertThat(jdbc.queryForObject(
			"SELECT status FROM problem_generation_requests WHERE id = ?",
			String.class,
			requestId
		)).isEqualTo("DELIVERY_FAILED");
	}

	private static java.time.OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}
}
