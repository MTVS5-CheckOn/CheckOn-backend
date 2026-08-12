package com.checkon.detection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.checkon.detection.integration.ai.AiDetectionRequestHeaders;
import com.checkon.detection.integration.ai.RiskDetectionClient;
import com.checkon.detection.integration.ai.RiskDetectionClientException;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class KafkaDetectionHttpAdapterTest {

	private static final String TENANT = "tn_0123456789abcdef0123456789abcdef";
	private static final String SNAPSHOT_HASH = "sha256:test";
	private static final UUID RUN_ID = UUID.fromString("019b0000-0000-7000-8000-000000000001");
	private static final UUID REQUEST_EVENT_ID = UUID.fromString("019b0000-0000-7000-8000-000000000002");
	private static final UUID ATTEMPT_ID = UUID.fromString("019b0000-0000-7000-8000-000000000003");
	private static final UUID OUTCOME_EVENT_ID = UUID.fromString("019b0000-0000-7000-8000-000000000005");

	private RiskDetectionClient client;
	private KafkaTemplate<String, String> kafkaTemplate;
	private KafkaDetectionHttpAdapter adapter;
	private ObjectMapper objectMapper;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		client = mock(RiskDetectionClient.class);
		kafkaTemplate = mock(KafkaTemplate.class);
		objectMapper = new ObjectMapper();
		RiskDetectionKafkaProperties properties = new RiskDetectionKafkaProperties(
			true, "requested", "completed", "failed", "adapter-group", "result-group",
			Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
			Duration.ofSeconds(1), 10, 3
		);
		DetectionIdGenerator ids = count -> List.of(OUTCOME_EVENT_ID);
		adapter = new KafkaDetectionHttpAdapter(
			client, ids, kafkaTemplate, properties, objectMapper,
			Clock.fixed(Instant.parse("2026-08-12T01:10:00Z"), ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("Given requested 이벤트, When AI가 200을 반환하면, Then 계약 헤더로 호출하고 completed를 발행한다")
	void givenRequestedEvent_whenAiReturns200_thenPublishesCompleted() throws Exception {
		when(client.detect(any(), any())).thenReturn(successResponse());
		when(kafkaTemplate.send(eq("completed"), eq(TENANT), any()))
			.thenReturn(CompletableFuture.completedFuture(null));

		adapter.handleRequested(TENANT, requestedEvent());

		ArgumentCaptor<AiDetectionRequestHeaders> headers =
			ArgumentCaptor.forClass(AiDetectionRequestHeaders.class);
		verify(client).detect(any(AiDetectionRequest.class), headers.capture());
		assertThat(headers.getValue().tenantId()).isEqualTo(TENANT);
		assertThat(headers.getValue().requestId()).isEqualTo("original-request-id");
		assertThat(headers.getValue().idempotencyKey().value())
			.isEqualTo(TENANT + ":2026-08-12");

		JsonNode published = published("completed");
		assertThat(published.get("event_type").asText()).isEqualTo("risk-detection.completed");
		assertThat(published.get("causation_id").asText())
			.isEqualTo(REQUEST_EVENT_ID.toString());
		assertThat(published.get("run_id").asText()).isEqualTo(RUN_ID.toString());
		assertThat(published.get("attempt_id").asText()).isEqualTo(ATTEMPT_ID.toString());
		assertThat(published.get("request_id").asText()).isEqualTo("original-request-id");
		assertThat(published.get("snapshot_hash").asText()).isEqualTo(SNAPSHOT_HASH);
	}

	@Test
	@DisplayName("Given requested 이벤트, When AI가 400이면, Then 재시도 없이 INVALID_SCHEMA failed를 발행한다")
	void givenRequestedEvent_whenAiReturns400_thenPublishesInvalidSchema() throws Exception {
		when(client.detect(any(), any())).thenThrow(RiskDetectionClientException.httpError(400, null));
		when(kafkaTemplate.send(eq("failed"), eq(TENANT), any()))
			.thenReturn(CompletableFuture.completedFuture(null));

		adapter.handleRequested(TENANT, requestedEvent());

		JsonNode published = published("failed");
		assertThat(published.at("/payload/code").asText()).isEqualTo("INVALID_SCHEMA");
		assertThat(published.at("/payload/retryable").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("Given requested 이벤트, When AI가 409이면, Then 재시도 없이 IDEMPOTENCY_CONFLICT를 발행한다")
	void givenRequestedEvent_whenAiReturns409_thenPublishesConflict() throws Exception {
		when(client.detect(any(), any()))
			.thenThrow(RiskDetectionClientException.idempotencyConflict(null));
		when(kafkaTemplate.send(eq("failed"), eq(TENANT), any()))
			.thenReturn(CompletableFuture.completedFuture(null));

		adapter.handleRequested(TENANT, requestedEvent());

		assertThat(published("failed").at("/payload/code").asText())
			.isEqualTo("IDEMPOTENCY_CONFLICT");
	}

	@Test
	@DisplayName("Given requested 이벤트, When AI가 5xx이면, Then Kafka 제한 재시도를 위해 예외를 다시 던진다")
	void givenRequestedEvent_whenAiReturns5xx_thenRethrows() {
		when(client.detect(any(), any())).thenThrow(RiskDetectionClientException.httpError(500, null));

		assertThatThrownBy(() -> adapter.handleRequested(TENANT, requestedEvent()))
			.isInstanceOf(RiskDetectionClientException.class);
		verify(kafkaTemplate, never()).send(any(), any(), any());
	}

	private JsonNode published(String topic) throws Exception {
		ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
		verify(kafkaTemplate).send(eq(topic), eq(TENANT), event.capture());
		return objectMapper.readTree(event.getValue());
	}

	private String requestedEvent() {
		return """
			{
			  "event_id":"%s", "event_type":"risk-detection.requested", "schema_version":"1.0",
			  "correlation_id":"%s", "causation_id":null, "tenant_alias":"%s",
			  "run_id":"%s", "attempt_id":"%s", "request_id":"original-request-id",
			  "idempotency_key":"%s:2026-08-12", "snapshot_hash":"%s",
			  "occurred_at":"2026-08-12T01:10:00Z",
			  "payload":{"snapshot_meta":{"week_start":"2026-08-10","snapshot_hash":"%s","term_context":"normal","classes":[]},
			    "students":[],"learning_events":[],"alert_context":[],"detection_evidence":[]}
			}
			""".formatted(REQUEST_EVENT_ID, RUN_ID, TENANT, RUN_ID, ATTEMPT_ID, TENANT,
			SNAPSHOT_HASH, SNAPSHOT_HASH);
	}

	private AiDetectionResponse successResponse() {
		return new AiDetectionResponse(
			new AiDetectionResponse.Data(
				List.of(), new AiDetectionResponse.Stats(0, 0, 0, 0, List.of())
			),
			null,
			new AiDetectionResponse.Meta("ai-execution-1", Map.of("pipeline", "test"))
		);
	}
}
