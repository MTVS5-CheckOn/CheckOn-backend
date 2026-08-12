package com.checkon.problem.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

@SpringBootTest
@Testcontainers
@DisplayName("문제 출제 결과 이벤트 처리")
class ProblemGenerationResultEventHandlerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198fa00-0000-7000-8000-000000000001");
	private static final UUID STUDENT =
		UUID.fromString("0198fa00-0000-7000-8000-000000000011");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-11T00:00:00Z");

	@Autowired JdbcTemplate jdbc;
	@Autowired ProblemGenerationRequestService requestService;
	@Autowired ProblemGenerationResultEventHandler handler;

	private UUID requestId;
	private String tenantAlias;

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
			VALUES (?, 'result-student', 1, ?, ?)
			""", STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, time(), time());
		requestId = requestService.create(TEACHER, new CreateProblemGenerationCommand(
			ProblemTargetKind.STUDENT,
			STUDENT,
			List.of("skill.grammar.001"),
			"2026.08",
			List.of(ProblemTypeTag.CONCEPT),
			2,
			ProblemDifficulty.MEDIUM,
			"result-handler-key-0001"
		)).requestId();
		tenantAlias = jdbc.queryForObject(
			"SELECT tenant_alias FROM problem_generation_requests WHERE id = ?",
			String.class,
			requestId
		);
	}

	@Nested
	@DisplayName("Given 정상 완료 이벤트가 있을 때")
	class GivenAValidSuccessEvent {

		@Test
		@DisplayName("When 이벤트를 처리하면 Then 원본 결과와 버전 정보를 백엔드 DB에 보존한다")
		void mirrorsTheRawResultAndVersions() {
			String event = succeededEvent(UUID.randomUUID(), "job-1", "set-1");

			handler.handle(event);

			var stored = jdbc.queryForMap("""
				SELECT status, ai_job_id, ai_execution_id, ai_set_id,
				       ai_result_status, result_payload::text AS result_payload,
				       versions_payload::text AS versions_payload, completed_at
				FROM problem_generation_requests WHERE id = ?
				""", requestId);
			assertThat(stored.get("status")).isEqualTo("SUCCEEDED");
			assertThat(stored.get("ai_job_id")).isEqualTo("job-1");
			assertThat(stored.get("ai_execution_id")).isEqualTo("execution-1");
			assertThat(stored.get("ai_set_id")).isEqualTo("set-1");
			assertThat(stored.get("ai_result_status")).isEqualTo("completed");
			assertThat((String) stored.get("result_payload")).contains("problem-1", "정답");
			assertThat((String) stored.get("versions_payload")).contains("model", "prompt");
			assertThat(stored.get("completed_at")).isNotNull();
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_consumed_events",
				Integer.class
			)).isEqualTo(1);
		}

		@Test
		@DisplayName("When 동일한 이벤트를 재처리하면 Then 상태와 소비 이력을 한 번만 반영한다")
		void consumesTheSameEventIdempotently() {
			UUID eventId = UUID.randomUUID();
			String event = succeededEvent(eventId, "job-2", "set-2");

			handler.handle(event);
			handler.handle(event);

			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_consumed_events WHERE event_id = ?",
				Integer.class,
				eventId
			)).isEqualTo(1);
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			)).isEqualTo("SUCCEEDED");
		}
	}

	@Nested
	@DisplayName("Given 이벤트 계약이 충돌할 때")
	class GivenAConflictingEventContract {

		@Test
		@DisplayName("When 같은 event_id에 다른 payload가 오면 Then 재사용을 거절한다")
		void rejectsAnEventIdReusedWithAnotherPayload() {
			UUID eventId = UUID.randomUUID();
			handler.handle(succeededEvent(eventId, "job-3", "set-3"));

			assertThatThrownBy(() -> handler.handle(succeededEvent(eventId, "job-changed", "set-3")))
				.isInstanceOf(ProblemGenerationEventContractException.class)
				.hasMessageContaining("event_id was reused");
		}

		@Test
		@DisplayName("When 완료 후 상충하는 실패 이벤트가 오면 Then 완료 결과를 바꾸지 않는다")
		void protectsATerminalResultFromContradictoryEvents() {
			handler.handle(succeededEvent(UUID.randomUUID(), "job-4", "set-4"));

			assertThatThrownBy(() -> handler.handle(failedEvent(UUID.randomUUID(), "job-4")))
				.isInstanceOf(ProblemGenerationEventContractException.class)
				.hasMessageContaining("terminal result");
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			)).isEqualTo("SUCCEEDED");
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_consumed_events",
				Integer.class
			)).isEqualTo(1);
		}

		@Test
		@DisplayName("When 요청과 다른 테넌트 alias가 오면 Then 요청 상태를 변경하지 않는다")
		void rejectsAWrongTenantAlias() {
			String wrongAlias = "tn_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

			assertThatThrownBy(() -> handler.handle(succeededEvent(
				UUID.randomUUID(), "job-5", "set-5", wrongAlias
			)))
				.isInstanceOf(ProblemGenerationEventContractException.class)
				.hasMessageContaining("could not be resolved");
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			)).isEqualTo("QUEUED");
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_consumed_events",
				Integer.class
			)).isZero();
		}
	}

	private String succeededEvent(UUID eventId, String jobId, String setId) {
		return succeededEvent(eventId, jobId, setId, tenantAlias);
	}

	private String succeededEvent(UUID eventId, String jobId, String setId, String eventTenantAlias) {
		return """
			{
			  "event_id": "%s",
			  "event_type": "worker_job.succeeded",
			  "occurred_at": "%s",
			  "tenant_id": "%s",
			  "schema_version": "worker-job-1",
			  "correlation_id": "%s",
			  "payload": {
			    "worker_kind": "problem_generation",
			    "problem_request_id": "%s",
			    "job_id": "%s",
			    "execution_id": "execution-1",
			    "set_id": "%s",
			    "result_status": "completed",
			    "result": {"set_id":"%s","problems":[{"id":"problem-1","answer":"정답"}]},
			    "versions": {"model":"m2-v1","prompt":"prompt-v1"}
			  }
			}
			""".formatted(eventId, eventTime(), eventTenantAlias, requestId, requestId, jobId, setId, setId);
	}

	private String failedEvent(UUID eventId, String jobId) {
		return """
			{
			  "event_id": "%s",
			  "event_type": "problem_generation.failed",
			  "occurred_at": "%s",
			  "tenant_id": "%s",
			  "schema_version": "pg-result-1",
			  "correlation_id": "%s",
			  "payload": {
			    "problem_request_id": "%s",
			    "job_id": "%s",
			    "error_code": "LLM_UNAVAILABLE"
			  }
			}
			""".formatted(eventId, eventTime(), tenantAlias, requestId, requestId, jobId);
	}

	private static String eventTime() {
		return Instant.now().plusSeconds(60).toString();
	}

	private static java.time.OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}
}
