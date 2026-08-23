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
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.checkon.problem.application.CreateProblemGenerationCommand;
import com.checkon.problem.application.CreateProblemStudioCommand;
import com.checkon.problem.application.ProblemGenerationRequestService;
import com.checkon.problem.domain.ProblemDifficulty;
import com.checkon.problem.domain.ProblemTargetKind;
import com.checkon.problem.domain.ProblemTypeTag;
import com.checkon.problem.infrastructure.outbox.ProblemGenerationOutboxPublisher;
import com.checkon.support.RosterTestFixture;

/**
 * Uses a real Testcontainers {@link KafkaContainer} instead of
 * {@code @EmbeddedKafka} -- the in-JVM embedded broker's KRaft shutdown has
 * repeatedly hung or OOM'd this repo's CI (see the sibling comment on
 * {@code counsel.integration.kafka.KafkaCounselDraftOutboxPublisherTest}). A
 * containerized broker has its own separately-managed lifecycle (Ryuk), so a
 * shutdown race here can no longer block the whole test JVM from exiting.
 */
@SpringBootTest(properties = {
	"checkon.ai.problem-generation.kafka.enabled=true",
	"checkon.ai.problem-generation.kafka.request-topic=problem-generation-requests-test",
	"checkon.ai.problem-generation.kafka.result-topic=problem-generation-results-test",
	"checkon.ai.problem-generation.kafka.dead-letter-topic=problem-generation-results-dlt-test",
	"checkon.ai.problem-generation.kafka.consumer-group=problem-generation-backend-test",
	"checkon.ai.problem-generation.kafka.outbox-poll-delay=1h"
})
@Testcontainers
@DisplayName("문제 출제 Kafka 왕복 흐름")
class ProblemGenerationKafkaFlowIntegrationTest {

	static final String REQUEST_TOPIC = "problem-generation-requests-test";
	static final String RESULT_TOPIC = "problem-generation-results-test";
	static final String DLT_TOPIC = "problem-generation-results-dlt-test";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4");

	@Container
	@ServiceConnection
	static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

	private static final UUID TEACHER =
		UUID.fromString("0198fb00-0000-7000-8000-000000000001");
	private static final UUID STUDENT =
		UUID.fromString("0198fb00-0000-7000-8000-000000000011");
	private static final Instant FIXTURE_TIME = Instant.parse("2026-08-11T00:00:00Z");

	@Autowired JdbcTemplate jdbc;
	@Autowired ProblemGenerationRequestService requestService;
	@Autowired ProblemGenerationOutboxPublisher outboxPublisher;
	@Autowired KafkaTemplate<String, String> kafkaTemplate;

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
		jdbc.update("DELETE FROM problem_diagnosis_snapshots");
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
			UUID diagnosisId=insertGeneratedDiagnosis();
			UUID requestId = requestService.createStudio(TEACHER, new CreateProblemStudioCommand(
				STUDENT,diagnosisId, List.of(
					new CreateProblemStudioCommand.Target("language",ProblemTypeTag.CONCEPT,3,
						"node.concept",null,null),
					new CreateProblemStudioCommand.Target("language",ProblemTypeTag.INFER,2,
						"node.infer",null,null)),
				ProblemDifficulty.MEDIUM,"studio-kafka-flow-0001")).requestId();
			String tenantAlias = tenantAlias(requestId);
			try (Consumer<String,String> consumer = consumer("studio-request-observer-"+UUID.randomUUID())) {
				subscribeAndAwaitAssignment(consumer, REQUEST_TOPIC);
				outboxPublisher.publishPending();
				var childRecords = awaitRecords(consumer, REQUEST_TOPIC,
					record -> record.value().contains(requestId.toString()), 2, Duration.ofSeconds(10));
				assertThat(childRecords).hasSize(2);
				assertThat(childRecords).allSatisfy(record -> {
					assertThat(record.key()).isEqualTo(tenantAlias);
					assertThat(record.value()).contains(requestId.toString(),"problem_execution_id","target_index","teacher_manual")
						.doesNotContain(TEACHER.toString(),STUDENT.toString(),"teacher_weakness_selection");
					assertThat(header(record,"schema_version")).isEqualTo("pg-child-request-2");
				});
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_generation_executions WHERE problem_request_id=? AND status='DISPATCHED'",Integer.class,requestId)).isEqualTo(2);
		}

		private UUID insertGeneratedDiagnosis() {
			UUID id=UUID.randomUUID(); String hash="sha256:"+"c".repeat(64);
			String response="""
				{"data":{"status":"generated","weakness_map":{"graph_version":"graph-v1","taxonomy_version":"v1","config_version":"config-v1",
				"snapshot_hash":"%s","nodes":{"node.concept":{"verdict":"suspect","basis":["cell:language×concept"]},
				"node.infer":{"verdict":"suspect","basis":["cell:language×infer"]}},
				"propagated":{"concept.root.1":{"score":3.0,"from_nodes":["node.concept"]},
				"concept.root.2":{"score":2.0,"from_nodes":["node.concept"]},"concept.root.3":{"score":1.0,"from_nodes":["node.concept"]},
				"infer.root.1":{"score":3.0,"from_nodes":["node.infer"]},"infer.root.2":{"score":2.0,"from_nodes":["node.infer"]}}}}}
				""".formatted(hash);
			jdbc.update("""
				INSERT INTO problem_diagnosis_snapshots(id,teacher_id,student_id,student_ref,status,snapshot_hash,taxonomy_version,
				 graph_version,config_version,request_payload,response_payload,diagnosed_at,created_at)
				VALUES (?,?,?,'st_0123456789abcdef0123456789abcdef','GENERATED',?,'v1','graph-v1','config-v1','{}'::jsonb,CAST(? AS jsonb),?,?)
				""",id,TEACHER,STUDENT,hash,response,time(),time());
			return id;
		}

		@Test
		@DisplayName("When Outbox를 발행하고 AI 성공 이벤트를 받으면 Then Kafka 계약과 DB 상태가 함께 완성된다")
		void publishesTheRequestAndConsumesTheResult() throws Exception {
			UUID requestId = createRequest("kafka-flow-key-0001");
			String tenantAlias = tenantAlias(requestId);
			try (Consumer<String, String> requestConsumer = consumer("request-observer-" + UUID.randomUUID())) {
				subscribeAndAwaitAssignment(requestConsumer, REQUEST_TOPIC);

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
				subscribeAndAwaitAssignment(dltConsumer, DLT_TOPIC);

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
		Map<String, Object> properties = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), groupId, false);
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<String, String>(properties).createConsumer();
	}

	/**
	 * A real broker's group-join/rebalance is not instant like the old
	 * {@code EmbeddedKafkaBroker#consumeFromAnEmbeddedTopic} helper made it
	 * look. Forcing the initial assignment here -- before the producer sends
	 * anything -- keeps that latency out of each test's fixed record-wait
	 * window instead of eating into it.
	 */
	private static void subscribeAndAwaitAssignment(Consumer<String, String> consumer, String topic) {
		consumer.subscribe(List.of(topic));
		Instant deadline = Instant.now().plusSeconds(10);
		while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
			consumer.poll(Duration.ofMillis(100));
		}
		assertThat(consumer.assignment()).isNotEmpty();
	}

	/**
	 * A real broker may deliver a producer's several sends across more than
	 * one poll batch (unlike the old in-process embedded broker), so a single
	 * {@code KafkaTestUtils.getRecords} call can race and see only part of
	 * them. Polling in a loop until enough of *this test's own* records
	 * (matched by {@code matcher}) have arrived avoids that race without
	 * caring how many unrelated records other tests left on the shared topic.
	 */
	private static List<ConsumerRecord<String, String>> awaitRecords(
		Consumer<String, String> consumer,
		String topic,
		java.util.function.Predicate<ConsumerRecord<String, String>> matcher,
		int expectedCount,
		Duration timeout
	) {
		List<ConsumerRecord<String, String>> matched = new java.util.ArrayList<>();
		Instant deadline = Instant.now().plus(timeout);
		while (matched.size() < expectedCount && Instant.now().isBefore(deadline)) {
			for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200)).records(topic)) {
				if (matcher.test(record)) matched.add(record);
			}
		}
		return matched;
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
