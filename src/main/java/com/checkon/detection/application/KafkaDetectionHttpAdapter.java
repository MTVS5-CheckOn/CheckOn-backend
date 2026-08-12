package com.checkon.detection.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.checkon.detection.integration.ai.AiDetectionRequestHeaders;
import com.checkon.detection.integration.ai.RiskDetectionClient;
import com.checkon.detection.integration.ai.RiskDetectionClientException;
import com.checkon.detection.integration.ai.dto.AiDetectionRequest;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.detection.integration.kafka.AiDetectionFailure;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaEvent;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bridges an internal requested Kafka event to the AI HTTP API and back to Kafka. */
@Service
public class KafkaDetectionHttpAdapter {
	private static final Logger log = LoggerFactory.getLogger(
		KafkaDetectionHttpAdapter.class
	);

	private final RiskDetectionClient riskDetectionClient;
	private final DetectionIdGenerator idGenerator;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final RiskDetectionKafkaProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public KafkaDetectionHttpAdapter(
		RiskDetectionClient riskDetectionClient,
		DetectionIdGenerator idGenerator,
		KafkaTemplate<String, String> kafkaTemplate,
		RiskDetectionKafkaProperties properties,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.riskDetectionClient = riskDetectionClient;
		this.idGenerator = idGenerator;
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public void handleRequested(String messageKey, String rawEvent) {
		RequestedEvent request = parseRequested(messageKey, rawEvent);
		try {
			AiDetectionResponse response = riskDetectionClient.detectRaw(
				request.payloadJson(),
				new AiDetectionRequestHeaders(
					request.tenantAlias(),
					request.requestId(),
					new DetectionExecutionKey(request.idempotencyKey())
				)
			);
			publishOutcome(request, RiskDetectionKafkaEvent.COMPLETED,
				properties.completedTopic(), response);
		}
		catch (RiskDetectionClientException exception) {
			if (isRetryable(exception)) throw exception;
			Map<String, Object> detail = terminalFailureDetail(exception);
			log.warn(
				"AI detection request rejected: runId={}, httpStatus={}, aiErrorCode={}, invalidFields={}",
				request.runId(),
				exception.httpStatus(),
				detail.get("ai_error_code"),
				detail.get("invalid_fields")
			);
			publishOutcome(request, RiskDetectionKafkaEvent.FAILED,
				properties.failedTopic(), terminalFailure(exception, detail));
		}
	}

	public void handleRetryExhausted(String messageKey, String rawEvent) {
		RequestedEvent request = parseRequested(messageKey, rawEvent);
		publishOutcome(request, RiskDetectionKafkaEvent.FAILED, properties.failedTopic(),
			new AiDetectionFailure(
				"AI_HTTP_RETRY_EXHAUSTED",
				"AI HTTP request failed after Kafka retries",
				Map.of("transport", "http"),
				true
			));
	}

	private boolean isRetryable(RiskDetectionClientException exception) {
		return switch (exception.reason()) {
			case NETWORK_ERROR, EMPTY_RESPONSE -> true;
			case HTTP_ERROR -> exception.httpStatus() == null || exception.httpStatus() >= 500;
			case IDEMPOTENCY_CONFLICT -> false;
		};
	}

	private AiDetectionFailure terminalFailure(
		RiskDetectionClientException exception,
		Map<String, Object> detail
	) {
		String code = exception.reason() == RiskDetectionClientException.Reason.IDEMPOTENCY_CONFLICT
			? "IDEMPOTENCY_CONFLICT"
			: "AI_HTTP_" + exception.httpStatus();
		return new AiDetectionFailure(
			code,
			"AI rejected the detection request",
			detail,
			false
		);
	}

	private Map<String, Object> terminalFailureDetail(
		RiskDetectionClientException exception
	) {
		Map<String, Object> detail = new LinkedHashMap<>();
		if (exception.httpStatus() != null) {
			detail.put("http_status", exception.httpStatus());
		}
		String responseBody = exception.responseBody();
		if (responseBody == null || responseBody.isBlank()) return Map.copyOf(detail);
		try {
			JsonNode error = objectMapper.readTree(responseBody).get("error");
			if (error == null || error.isNull()) return Map.copyOf(detail);
			String aiErrorCode = text(error, "code");
			if (aiErrorCode != null && !aiErrorCode.isBlank()) {
				detail.put("ai_error_code", aiErrorCode);
			}
			JsonNode violations = error.get("detail");
			if (violations != null && violations.isArray()) {
				List<String> invalidFields = new ArrayList<>();
				for (JsonNode violation : violations) {
					String field = text(violation, "field");
					if (field != null && !field.isBlank()) invalidFields.add(field);
				}
				if (!invalidFields.isEmpty()) {
					detail.put("invalid_fields", List.copyOf(invalidFields));
				}
			}
		}
		catch (JacksonException ignored) {
			// The remote body can be HTML or malformed JSON. Never copy the raw
			// response into Kafka or logs because it may contain request values.
		}
		return Map.copyOf(detail);
	}

	private void publishOutcome(
		RequestedEvent request,
		String eventType,
		String topic,
		Object payload
	) {
		UUID eventId = idGenerator.nextIds(1).getFirst();
		RiskDetectionKafkaEvent<Object> outcome = new RiskDetectionKafkaEvent<>(
			eventId, eventType, RiskDetectionKafkaEvent.SCHEMA_VERSION,
			request.runId(), request.eventId(), request.tenantAlias(), request.runId(),
			request.attemptId(), request.requestId(), request.idempotencyKey(),
			request.snapshotHash(), Instant.now(clock), payload
		);
		try {
			kafkaTemplate.send(topic, request.tenantAlias(), objectMapper.writeValueAsString(outcome))
				.get(properties.producerSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Kafka outcome publish was interrupted", exception);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Kafka outcome publish failed", exception);
		}
	}

	private RequestedEvent parseRequested(String messageKey, String rawEvent) {
		try {
			JsonNode root = objectMapper.readTree(rawEvent);
			if (!RiskDetectionKafkaEvent.REQUESTED.equals(text(root, "event_type"))) {
				throw new IllegalArgumentException("Unexpected risk detection event_type");
			}
			if (!RiskDetectionKafkaEvent.SCHEMA_VERSION.equals(text(root, "schema_version"))) {
				throw new IllegalArgumentException("Unsupported risk detection schema_version");
			}
			String tenantAlias = requiredText(text(root, "tenant_alias"), "tenant_alias");
			if (!tenantAlias.equals(messageKey)) {
				throw new IllegalArgumentException("Kafka key must equal tenant_alias");
			}
			JsonNode payload = root.get("payload");
			if (payload == null || payload.isNull()) {
				throw new IllegalArgumentException("Kafka event payload must not be null");
			}
			UUID runId = uuid(root, "run_id");
			if (!runId.toString().equals(requiredText(text(root, "correlation_id"), "correlation_id"))) {
				throw new IllegalArgumentException("correlation_id must equal run_id");
			}
			AiDetectionRequest aiRequest = objectMapper.treeToValue(payload, AiDetectionRequest.class);
			String payloadJson = objectMapper.writeValueAsString(payload);
			String snapshotHash = requiredText(text(root, "snapshot_hash"), "snapshot_hash");
			if (aiRequest.snapshotMeta() == null
				|| !snapshotHash.equals(aiRequest.snapshotMeta().snapshotHash())) {
				throw new IllegalArgumentException(
					"Envelope snapshot_hash must equal payload snapshot_meta.snapshot_hash"
				);
			}
			return new RequestedEvent(
				uuid(root, "event_id"), tenantAlias, runId,
				uuid(root, "attempt_id"), requiredText(text(root, "request_id"), "request_id"),
				requiredText(text(root, "idempotency_key"), "idempotency_key"),
				snapshotHash, aiRequest, payloadJson
			);
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("Kafka requested event JSON is invalid", exception);
		}
	}

	private UUID uuid(JsonNode root, String field) {
		return UUID.fromString(requiredText(text(root, field), field));
	}

	private String text(JsonNode root, String field) {
		JsonNode value = root.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private String requiredText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	private record RequestedEvent(
		UUID eventId,
		String tenantAlias,
		UUID runId,
		UUID attemptId,
		String requestId,
		String idempotencyKey,
		String snapshotHash,
		AiDetectionRequest payload,
		String payloadJson
	) {
	}
}
