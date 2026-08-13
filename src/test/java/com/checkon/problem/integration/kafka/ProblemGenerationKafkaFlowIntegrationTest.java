package com.checkon.problem.integration.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.problem.application.CreateProblemGenerationCommand;
import com.checkon.problem.application.CreateProblemStudioCommand;
import com.checkon.problem.application.ProblemGenerationRequestService;
import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTargetKind;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxPublisher;
import com.checkon.support.RosterTestFixture;

@SpringBootTest(properties = {
	"checkon.ai.problem-generation.kafka.enabled=true",
	"checkon.ai.problem-generation.kafka.request-topic=problem-generation-requests-test",
	"checkon.ai.problem-generation.kafka.result-topic=problem-generation-results-test",
	"checkon.ai.problem-generation.kafka.dead-letter-topic=problem-generation-results-dlt-test",
	"checkon.ai.problem-generation.kafka.consumer-group=problem-generation-backend-test",
	"checkon.ai.problem-generation.kafka.outbox-poll-delay=1h",
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		ProblemGenerationKafkaFlowIntegrationTest.REQUEST_TOPIC,
		ProblemGenerationKafkaFlowIntegrationTest.RESULT_TOPIC,
		ProblemGenerationKafkaFlowIntegrationTest.DLT_TOPIC
	}
)
@Testcontainers
@DisplayName("문제 출제 Kafka 왕복 흐름")
class ProblemGenerationKafkaFlowIntegrationTest {

	static final String REQUEST_TOPIC = "problem-generation-requests-test";
	static final String RESULT_TOPIC = "problem-generation-results-test";
	static final String DLT_TOPIC = "problem-generation-results-dlt-test";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	private static final UUID TEACHER =
		UUID.fromString("0198fb00-0000-7000-8000-000000000001");
	private static final UUID STUDENT =
		UUID.fromString("0198fb00-0000-7000-8000-000000000011");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-11T00:00:00Z");

	@Autowired JdbcTemplate jdbc;
	@Autowired ProblemGenerationRequestService requestService;
	@Autowired ProblemGenerationOutboxPublisher outboxPublisher;
	@Autowired KafkaTemplate<String, String> kafkaTemplate;
	@Autowired EmbeddedKafkaBroker embeddedKafka;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM problem_assignments");
		jdbc.update("DELETE FROM saved_problem_set_items");
		jdbc.update("DELETE FROM saved_problem_sets");
		jdbc.update("DELETE FROM problem_generation_item_options");
		jdbc.update("DELETE FROM problem_generation_items");
		jdbc.update("DELETE FROM problem_generation_consumed_events");
		jdbc.update("DELETE FROM problem_generation_outbox");
		jdbc.update("DELETE FROM problem_generation_executions");
		jdbc.update("DELETE FROM problem_generation_request_targets");
		jdbc.update("DELETE FROM problem_generation_requests");
		jdbc.update("DELETE FROM ai_tenant_aliases");
		jdbc.update("DELETE FROM ai_student_aliases");
		jdbc.update("DELETE FROM teacher_student_relationships");
		jdbc.update("DELETE FROM student_profiles");
		RosterTestFixture.insertTeacher(jdbc, TEACHER);
		jdbc.update("""
			INSERT INTO student_profiles (id, alias, grade, created_at, updated_at)
			VALUES (?, 'kafka-student', 1, ?, ?)
			""", STUDENT, time(), time());
		jdbc.update("""
			INSERT INTO teacher_student_relationships
			    (teacher_id, student_id, status, started_at, created_at)
			VALUES (?, ?, 'ACTIVE', ?, ?)
			""", TEACHER, STUDENT, time(), time());
	}

	@Nested
	@DisplayName("Given 저장된 출제 요청이 있을 때")
	class GivenAStoredProblemRequest {
		@Test
		@DisplayName("When 스튜디오 복수 셀을 요청하면 Then target별 child Kafka 이벤트를 각각 발행한다")
		void publishesOneKafkaEventPerStudioTarget() throws Exception {
			UUID requestId = requestService.createStudio(TEACHER, new CreateProblemStudioCommand(
				STUDENT, List.of(
					new CreateProblemStudioCommand.Target("language",ProblemTypeTag.CONCEPT,3),
					new CreateProblemStudioCommand.Target("language",ProblemTypeTag.INFER,2)),
				ProblemDifficulty.MEDIUM,"studio-kafka-flow-0001")).requestId();
			String tenantAlias = tenantAlias(requestId);
			try (Consumer<String,String> consumer = consumer("studio-request-observer-"+UUID.randomUUID())) {
				embeddedKafka.consumeFromAnEmbeddedTopic(consumer,REQUEST_TOPIC);
				outboxPublisher.publishPending();
				var records = KafkaTestUtils.getRecords(consumer,Duration.ofSeconds(10));
				var childRecords = java.util.stream.StreamSupport.stream(records.records(REQUEST_TOPIC).spliterator(),false)
					.filter(record -> record.value().contains(requestId.toString())).toList();
				assertThat(childRecords).hasSize(2);
				assertThat(childRecords).allSatisfy(record -> {
					assertThat(record.key()).isEqualTo(tenantAlias);
					assertThat(record.value()).contains(requestId.toString(),"problem_execution_id","target_index","teacher_manual")
						.doesNotContain(TEACHER.toString(),STUDENT.toString(),"teacher_weakness_selection");
					assertThat(header(record,"schema_version")).isEqualTo("pg-child-request-1");
				});
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_executions WHERE problem_request_id=? AND status='DISPATCHED'",Integer.class,requestId)).isEqualTo(2);
		}

		@Test
		@DisplayName("When Outbox를 발행하고 AI 성공 이벤트를 받으면 Then Kafka 계약과 DB 상태가 함께 완성된다")
		void publishesTheRequestAndConsumesTheResult() throws Exception {
			UUID requestId = createRequest("kafka-flow-key-0001");
			String tenantAlias = tenantAlias(requestId);
			try (Consumer<String, String> requestConsumer = consumer("request-observer-" + UUID.randomUUID())) {
				embeddedKafka.consumeFromAnEmbeddedTopic(requestConsumer, REQUEST_TOPIC);

				outboxPublisher.publishPending();

				ConsumerRecord<String, String> request = KafkaTestUtils.getSingleRecord(
					requestConsumer,
					REQUEST_TOPIC,
					Duration.ofSeconds(10)
				);
				assertThat(request.key()).isEqualTo(tenantAlias);
				assertThat(request.value())
					.contains(requestId.toString(), tenantAlias, "problem_generation.requested")
					.doesNotContain(TEACHER.toString(), STUDENT.toString());
				assertThat(header(request, "event_type")).isEqualTo("problem_generation.requested");
				assertThat(header(request, "schema_version")).isEqualTo("pg-request-1");
				assertThat(header(request, "correlation_id")).isEqualTo(requestId.toString());
				assertThat(header(request, "tenant_id")).isEqualTo(tenantAlias);
			}

			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			)).isEqualTo("DISPATCHED");
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_outbox WHERE problem_request_id = ?",
				String.class,
				requestId
			)).isEqualTo("PUBLISHED");

			kafkaTemplate.send(RESULT_TOPIC, tenantAlias, successEvent(requestId, tenantAlias)).get();

			awaitStatus(requestId, "SUCCEEDED");
			assertThat(jdbc.queryForObject(
				"SELECT ai_set_id FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			)).isEqualTo("set-kafka-1");
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_consumed_events WHERE problem_request_id = ?",
				Integer.class,
				requestId
			)).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("Given 내부 UUID를 tenant_id로 사용한 잘못된 결과 이벤트가 있을 때")
	class GivenAnInvalidTenantContract {

		@Test
		@DisplayName("When 결과 토픽으로 수신하면 Then 재시도하지 않고 DLT로 격리한다")
		void routesTheContractFailureToTheDeadLetterTopic() throws Exception {
			UUID requestId = createRequest("kafka-flow-key-0002");
			String invalidEvent = successEvent(requestId, TEACHER.toString());
			try (Consumer<String, String> dltConsumer = consumer("dlt-observer-" + UUID.randomUUID())) {
				embeddedKafka.consumeFromAnEmbeddedTopic(dltConsumer, DLT_TOPIC);

				kafkaTemplate.send(RESULT_TOPIC, TEACHER.toString(), invalidEvent).get();

				ConsumerRecord<String, String> deadLetter = KafkaTestUtils.getSingleRecord(
					dltConsumer,
					DLT_TOPIC,
					Duration.ofSeconds(10)
				);
				assertThat(deadLetter.value()).isEqualTo(invalidEvent);
			}
			assertThat(jdbc.queryForObject(
				"SELECT status FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			)).isEqualTo("QUEUED");
			assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM problem_generation_consumed_events WHERE problem_request_id = ?",
				Integer.class,
				requestId
			)).isZero();
		}
	}

	private UUID createRequest(String idempotencyKey) {
		return requestService.create(TEACHER, new CreateProblemGenerationCommand(
			ProblemTargetKind.STUDENT,
			STUDENT,
			List.of("skill.grammar.001"),
			"2026.08",
			List.of(ProblemTypeTag.CONCEPT),
			2,
			ProblemDifficulty.MEDIUM,
			idempotencyKey
		)).requestId();
	}

	private Consumer<String, String> consumer(String groupId) {
		Map<String, Object> properties = KafkaTestUtils.consumerProps(embeddedKafka, groupId, false);
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<String, String>(properties).createConsumer();
	}

	private String tenantAlias(UUID requestId) {
		return jdbc.queryForObject(
			"SELECT tenant_alias FROM problem_generation_requests WHERE id = ?",
			String.class,
			requestId
		);
	}

	private String successEvent(UUID requestId, String eventTenantId) {
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
			    "job_id": "job-kafka-1",
			    "execution_id": "execution-kafka-1",
			    "set_id": "set-kafka-1",
			    "result_status": "completed",
			    "result": {"set_id":"set-kafka-1","problems":[{"id":"problem-kafka-1"}]},
			    "versions": {"model":"m2-v1"}
			  }
			}
			""".formatted(UUID.randomUUID(), Instant.now().plusSeconds(60), eventTenantId, requestId, requestId);
	}

	private void awaitStatus(UUID requestId, String expected) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		String status;
		do {
			status = jdbc.queryForObject(
				"SELECT status FROM problem_generation_requests WHERE id = ?",
				String.class,
				requestId
			);
			if (expected.equals(status)) {
				return;
			}
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);
		assertThat(status).isEqualTo(expected);
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}

	private static java.time.OffsetDateTime time() {
		return FIXTURE_TIME.atOffset(ZoneOffset.UTC);
	}
}
