package com.checkon.counsel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.counsel.domain.CounselTopic;
import com.checkon.counsel.domain.CounselUrgency;
import com.checkon.counsel.infrastructure.persistence.CounselDraftJobRepository;
import com.checkon.counsel.integration.ai.CounselClient;
import com.checkon.counsel.integration.kafka.CounselDraftKafkaEvent;
import com.checkon.problem.application.AiProblemAliasService;
import com.checkon.support.RosterTestFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Mirrors {@code detection.application.KafkaDetectionResultConsumerIntegrationTest}:
 * calls the consumer directly with hand-built envelope JSON instead of going
 * through a real broker (no {@code @EmbeddedKafka} — see
 * {@code KafkaCounselDraftOutboxPublisherTest} for why). The requested
 * envelope is read straight from the outbox row {@link CounselDraftService}
 * inserted, exactly as an adapter would have received it.
 */
@SpringBootTest
@Testcontainers
@DisplayName("상담 초안 Kafka 결과 소비")
class KafkaCounselDraftResultConsumerIntegrationTest {

	private static final UUID TEACHER_ID = UUID.fromString("019846dc-7c00-7000-8000-000000000d01");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@MockitoBean
	private CounselClient client;

	@Autowired
	private CounselDraftService drafts;

	@Autowired
	private KafkaCounselDraftResultConsumer consumer;

	@Autowired
	private CounselDraftJobRepository jobRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AiProblemAliasService tenantAliases;

	private String tenantAlias;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM kafka_inbox_events");
		jdbc.update("DELETE FROM counsel_draft_kafka_outbox_events");
		jdbc.update("DELETE FROM counsel_draft_jobs");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		RosterTestFixture.insertTeacher(jdbc, TEACHER_ID);
		tenantAlias = tenantAliases.getOrCreateTenantAlias(TEACHER_ID);
	}

	@Nested
	@DisplayName("Given 발행된 요청 이벤트가 있을 때")
	class GivenARequestedEvent {

		@Test
		@DisplayName("When 완료 이벤트를 받으면 Then ai_job_id와 phase를 채운다")
		void appliesTheCompletionEvent() throws Exception {
			var created = drafts.createDraft(TEACHER_ID, sampleCommand("iq_901"));
			JsonNode request = requestEnvelope(created.jobId());

			consumer.consumeCompleted("checkon.counsel-draft.completed.v1", completionEvent(request, "cj_ai_901"));

			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, created.jobId()).orElseThrow();
			assertThat(stored.aiJobId()).isEqualTo("cj_ai_901");
			assertThat(stored.jobPhase()).isEqualTo("succeeded");
		}

		@Test
		@DisplayName("When 같은 완료 이벤트가 두 번 도착하면 Then 한 번만 적용된다(멱등)")
		void appliesADuplicateCompletionEventOnlyOnce() throws Exception {
			var created = drafts.createDraft(TEACHER_ID, sampleCommand("iq_902"));
			JsonNode request = requestEnvelope(created.jobId());
			String event = completionEvent(request, "cj_ai_902");

			consumer.consumeCompleted("checkon.counsel-draft.completed.v1", event);
			consumer.consumeCompleted("checkon.counsel-draft.completed.v1", event);

			assertThat(jdbc.queryForObject("SELECT count(*) FROM kafka_inbox_events", Integer.class)).isEqualTo(1);
			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, created.jobId()).orElseThrow();
			assertThat(stored.aiJobId()).isEqualTo("cj_ai_902");
		}

		@Test
		@DisplayName("When 실패 이벤트를 받으면 Then 로컬 잡을 failed로 표시한다")
		void appliesTheFailureEvent() throws Exception {
			var created = drafts.createDraft(TEACHER_ID, sampleCommand("iq_903"));
			JsonNode request = requestEnvelope(created.jobId());

			consumer.consumeFailed("checkon.counsel-draft.failed.v1", failureEvent(request));

			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, created.jobId()).orElseThrow();
			assertThat(stored.jobPhase()).isEqualTo("failed");
			assertThat(stored.aiJobId()).isNull();
		}

		@Test
		@DisplayName("When idempotency_key가 로컬 잡과 다르면 Then 예외를 던지고 잡을 갱신하지 않는다")
		void rejectsAMismatchedIdempotencyKey() throws Exception {
			var created = drafts.createDraft(TEACHER_ID, sampleCommand("iq_904"));
			JsonNode request = requestEnvelope(created.jobId());
			String tampered = completionEvent(request, "cj_ai_904")
				.replace(request.get("idempotency_key").asText(), "wrong-key");

			assertThatThrownBy(() -> consumer.consumeCompleted("checkon.counsel-draft.completed.v1", tampered))
				.isInstanceOf(IllegalArgumentException.class);

			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, created.jobId()).orElseThrow();
			assertThat(stored.aiJobId()).isNull();
		}

		@Test
		@DisplayName("When causation_id가 원본 요청 이벤트를 가리키지 않으면 Then 예외를 던지고 잡을 갱신하지 않는다")
		void rejectsAnUnknownCausationId() throws Exception {
			var created = drafts.createDraft(TEACHER_ID, sampleCommand("iq_905"));
			JsonNode request = requestEnvelope(created.jobId());
			String tampered = completionEvent(request, "cj_ai_905")
				.replace(request.get("event_id").asText(), UUID.randomUUID().toString());

			assertThatThrownBy(() -> consumer.consumeCompleted("checkon.counsel-draft.completed.v1", tampered))
				.isInstanceOf(IllegalArgumentException.class);

			var stored = jobRepository.findByTeacherAndJobId(TEACHER_ID, created.jobId()).orElseThrow();
			assertThat(stored.aiJobId()).isNull();
		}
	}

	private CreateCounselDraftCommand sampleCommand(String idempotencyKey) {
		return new CreateCounselDraftCommand(
			tenantAlias, "req-" + idempotencyKey, idempotencyKey, idempotencyKey,
			CounselTopic.GRADE, CounselUrgency.IMMEDIATE, OffsetDateTime.parse("2026-08-20T14:20:00+09:00"),
			"요즘 아이가 힘들어하는 것 같아요", "st_8f2a", "pa_9c1d", "cl_a1",
			List.of("narrative"), List.of(), "sha256:" + "a".repeat(64), "2026년 8월",
			List.of(new CreateCounselDraftCommand.Fact("le_2041", "6월 지문 42개·312문항"))
		);
	}

	private JsonNode requestEnvelope(String jobId) throws Exception {
		String payload = jdbc.queryForObject("""
			SELECT o.payload FROM counsel_draft_kafka_outbox_events o
			JOIN counsel_draft_jobs j ON j.id = o.counsel_draft_job_id
			WHERE j.job_id = ?
			""", String.class, jobId);
		return objectMapper.readTree(payload);
	}

	private String completionEvent(JsonNode request, String aiJobId) {
		return outcomeEnvelope(request, CounselDraftKafkaEvent.COMPLETED, """
			{"job_id":"%s","status":"succeeded","execution_id":"ai-exec-1"}
			""".formatted(aiJobId));
	}

	private String failureEvent(JsonNode request) {
		return outcomeEnvelope(request, CounselDraftKafkaEvent.FAILED, """
			{"code":"AI_TIMEOUT","message":"AI processing timed out","detail":"upstream detail","retryable":false}
			""");
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
			  "occurred_at":"2026-08-20T02:11:00Z",
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
