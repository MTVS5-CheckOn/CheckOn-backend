package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import com.checkon.detection.domain.DetectionRunStatus;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaProperties;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
class KafkaDetectionResultConsumerIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL =
		new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198b000-0000-7000-8000-000000000201");
	private static final UUID STUDENT =
		UUID.fromString("0198b000-0000-7000-8000-000000000202");
	private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 8, 3);

	@Autowired OperationalDetectionRunService runService;
	@Autowired KafkaDetectionResultConsumer consumer;
	@Autowired DetectionRunRepository runRepository;
	@Autowired RiskDetectionKafkaProperties kafkaProperties;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper objectMapper;

	@BeforeEach
	void fixtures() {
		jdbc.update("DELETE FROM kafka_inbox_events");
		jdbc.update("DELETE FROM kafka_outbox_events");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM detection_result_evidence");
		jdbc.update("DELETE FROM detection_signal_results");
		jdbc.update("DELETE FROM detection_request_attempts");
		jdbc.update("DELETE FROM detection_runs");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM learning_records");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		Instant now = Instant.parse("2026-06-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'Kafka 결과 테스트 학생', 1, ?, ?)
			""", STUDENT, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			(id, teacher_id, student_id, status, started_at, created_at)
			VALUES (uuidv7(), ?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
		jdbc.update("""
			INSERT INTO learning_records
			(id, teacher_id, student_id, record_type, occurred_at, source_type,
			 correct, duration_sec, created_at, updated_at)
			VALUES (uuidv7(), ?, ?, 'SOLVE', '2026-07-20T01:00:00Z', 'test', true, 120, now(), now())
			""", TEACHER, STUDENT);
	}

	@Test
	@DisplayName("Given 요청 Outbox가 있을 때, When AI 완료 이벤트를 두 번 받으면, Then 결과는 한 번만 저장되고 Run은 성공한다")
	void givenRequestedOutbox_whenCompletedEventArrivesTwice_thenStoresOnceAndSucceeds()
		throws Exception {
		var requested = runService.execute(TEACHER, ANALYSIS_DATE);
		String completed = completionEvent(requested.runId());

		consumer.consumeCompleted(kafkaProperties.completedTopic(), completed);
		consumer.consumeCompleted(kafkaProperties.completedTopic(), completed);

		var run = runRepository.findByTeacherIdAndAnalysisDate(TEACHER, ANALYSIS_DATE)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.SUCCEEDED);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM kafka_inbox_events", Integer.class))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM detection_request_attempts "
			+ "WHERE status = 'SUCCEEDED'", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("Given 요청 Outbox가 있을 때, When AI 실패 이벤트를 받으면, Then 해당 attempt와 Run을 실패로 기록한다")
	void givenRequestedOutbox_whenFailedEventArrives_thenMarksRunFailed() throws Exception {
		var requested = runService.execute(TEACHER, ANALYSIS_DATE);
		consumer.consumeFailed(kafkaProperties.failedTopic(), failureEvent(requested.runId()));

		var run = runRepository.findByTeacherIdAndAnalysisDate(TEACHER, ANALYSIS_DATE)
			.orElseThrow();
		assertThat(run.status()).isEqualTo(DetectionRunStatus.FAILED);
		assertThat(run.errorCode()).isEqualTo("AI_TIMEOUT");
	}

	private String completionEvent(UUID runId) throws Exception {
		JsonNode request = requestEnvelope(runId);
		return outcomeEnvelope(request, "risk-detection.completed", """
			{"data":{"signals":[],"stats":{"students_evaluated":1,"signals_raised":0,"excluded_under_2w":0,"capped_out":0,"rules_skipped":[]}},
			 "error":null,"meta":{"execution_id":"ai-test-1","versions":{"pipeline":"test"}}}
			""");
	}

	private String failureEvent(UUID runId) throws Exception {
		JsonNode request = requestEnvelope(runId);
		return outcomeEnvelope(request, "risk-detection.failed", """
			{"code":"AI_TIMEOUT","message":"AI processing timed out","detail":null,"retryable":false}
			""");
	}

	private JsonNode requestEnvelope(UUID runId) throws Exception {
		String payload = jdbc.queryForObject("""
			SELECT payload FROM kafka_outbox_events WHERE detection_run_id = ?
			""", String.class, runId);
		return objectMapper.readTree(payload);
	}

	private String outcomeEnvelope(JsonNode request, String eventType, String payload) {
		return """
			{
			  "event_id":"%s",
			  "event_type":"%s",
			  "schema_version":"1.0",
			  "correlation_id":"%s",
			  "causation_id":"%s",
			  "tenant_alias":"%s",
			  "run_id":"%s",
			  "attempt_id":"%s",
			  "request_id":"%s",
			  "idempotency_key":"%s",
			  "snapshot_hash":"%s",
			  "occurred_at":"2026-08-03T02:11:00Z",
			  "payload":%s
			}
			""".formatted(
			UUID.randomUUID(),
			eventType,
			request.get("correlation_id").asText(),
			request.get("event_id").asText(),
			request.get("tenant_alias").asText(),
			request.get("run_id").asText(),
			request.get("attempt_id").asText(),
			request.get("request_id").asText(),
			request.get("idempotency_key").asText(),
			request.get("snapshot_hash").asText(),
			payload
		);
	}
}
