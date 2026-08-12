package com.checkon.detection.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.detection.domain.DetectionRun;
import com.checkon.detection.infrastructure.kafka.AiTenantAliasService;
import com.checkon.detection.infrastructure.kafka.KafkaInboxRepository;
import com.checkon.detection.infrastructure.kafka.KafkaOutboxRepository;
import com.checkon.detection.infrastructure.persistence.DetectionRunRepository;
import com.checkon.detection.integration.ai.dto.AiDetectionResponse;
import com.checkon.detection.integration.kafka.AiDetectionFailure;
import com.checkon.detection.integration.kafka.RiskDetectionKafkaEvent;
import com.checkon.global.persistence.TeacherTenantDatabaseContext;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Consumes AI outcome events idempotently and applies them inside the tenant boundary. */
@Service
public class KafkaDetectionResultConsumer {

	private final AiTenantAliasService tenantAliasService;
	private final KafkaInboxRepository inboxRepository;
	private final KafkaOutboxRepository outboxRepository;
	private final DetectionRunRepository runRepository;
	private final DetectionResponseStorageService responseStorageService;
	private final DetectionAttemptCoordinator attemptCoordinator;
	private final TeacherTenantDatabaseContext tenantContext;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public KafkaDetectionResultConsumer(
		AiTenantAliasService tenantAliasService,
		KafkaInboxRepository inboxRepository,
		KafkaOutboxRepository outboxRepository,
		DetectionRunRepository runRepository,
		DetectionResponseStorageService responseStorageService,
		DetectionAttemptCoordinator attemptCoordinator,
		TeacherTenantDatabaseContext tenantContext,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.tenantAliasService = tenantAliasService;
		this.inboxRepository = inboxRepository;
		this.outboxRepository = outboxRepository;
		this.runRepository = runRepository;
		this.responseStorageService = responseStorageService;
		this.attemptCoordinator = attemptCoordinator;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public void consumeCompleted(String topic, String rawEvent) {
		ParsedEvent event = parse(rawEvent, RiskDetectionKafkaEvent.COMPLETED);
		AiDetectionResponse response = readPayload(event.payload(), AiDetectionResponse.class);
		UUID teacherId = tenantAliasService.requireTeacherId(event.tenantAlias());
		if (!inboxRepository.recordIfAbsent(event.eventId(), topic, Instant.now(clock))) return;

		DetectionRun run = currentRun(teacherId, event);
		if (run == null) return; // duplicate of an already superseded retry attempt
		responseStorageService.storeSuccessfulResponse(
			teacherId,
			event.runId(),
			event.attemptId(),
			null,
			response,
			Instant.now(clock)
		);
	}

	@Transactional
	public void consumeFailed(String topic, String rawEvent) {
		ParsedEvent event = parse(rawEvent, RiskDetectionKafkaEvent.FAILED);
		AiDetectionFailure failure = readPayload(event.payload(), AiDetectionFailure.class);
		UUID teacherId = tenantAliasService.requireTeacherId(event.tenantAlias());
		if (!inboxRepository.recordIfAbsent(event.eventId(), topic, Instant.now(clock))) return;

		DetectionRun run = currentRun(teacherId, event);
		if (run == null) return;
		String code = requiredText(failure.code(), "payload.code");
		if (code.length() > 60) {
			throw new IllegalArgumentException("payload.code must not exceed 60 characters");
		}
		requiredText(failure.message(), "payload.message");
		attemptCoordinator.fail(
			teacherId,
			event.runId(),
			event.attemptId(),
			null,
			code,
			Instant.now(clock)
		);
	}

	private DetectionRun currentRun(UUID teacherId, ParsedEvent event) {
		tenantContext.setCurrentTeacher(teacherId);
		DetectionRun run = runRepository.findByIdAndTeacherId(event.runId(), teacherId)
			.orElseThrow(DetectionExecutionException::runNotFound);
		String expectedIdempotencyKey = DetectionExecutionKey.daily(
			event.tenantAlias(), run.analysisDate()
		).value();
		String legacyIdempotencyKey = DetectionExecutionKey.daily(
			DetectionTenantKey.fromTeacherProfileId(teacherId).value(),
			run.analysisDate()
		).value();
		if (!event.idempotencyKey().equals(expectedIdempotencyKey)
			|| (!run.idempotencyKey().equals(expectedIdempotencyKey)
				&& !run.idempotencyKey().equals(legacyIdempotencyKey))
			|| !run.snapshotHash().equals(event.snapshotHash())) {
			throw new IllegalArgumentException("Kafka event does not match the detection run");
		}
		if (!event.correlationId().equals(event.runId())) {
			throw new IllegalArgumentException("correlation_id must equal run_id");
		}
		if (!outboxRepository.existsRequestedEvent(
			event.causationId(), event.runId(), event.attemptId()
		)) {
			throw new IllegalArgumentException("causation_id is not the original request event");
		}
		return run.isCurrentRequestedAttempt(event.attemptId(), event.requestId()) ? run : null;
	}

	private ParsedEvent parse(String rawEvent, String expectedType) {
		try {
			JsonNode root = objectMapper.readTree(rawEvent);
			String eventType = requiredText(text(root, "event_type"), "event_type");
			if (!expectedType.equals(eventType)) {
				throw new IllegalArgumentException("Unexpected risk detection event_type: " + eventType);
			}
			if (!RiskDetectionKafkaEvent.SCHEMA_VERSION.equals(text(root, "schema_version"))) {
				throw new IllegalArgumentException("Unsupported risk detection schema_version");
			}
			JsonNode payload = root.get("payload");
			if (payload == null || payload.isNull()) {
				throw new IllegalArgumentException("Kafka event payload must not be null");
			}
			return new ParsedEvent(
				uuid(root, "event_id"),
				uuid(root, "correlation_id"),
				uuid(root, "causation_id"),
				requiredText(text(root, "tenant_alias"), "tenant_alias"),
				uuid(root, "run_id"),
				uuid(root, "attempt_id"),
				requiredText(text(root, "request_id"), "request_id"),
				requiredText(text(root, "idempotency_key"), "idempotency_key"),
				requiredText(text(root, "snapshot_hash"), "snapshot_hash"),
				payload
			);
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("Kafka event JSON is invalid", exception);
		}
	}

	private <T> T readPayload(JsonNode payload, Class<T> type) {
		try {
			return objectMapper.treeToValue(payload, type);
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("Kafka event payload is invalid", exception);
		}
	}

	private UUID uuid(JsonNode root, String field) {
		try {
			return UUID.fromString(requiredText(text(root, field), field));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(field + " must be a UUID", exception);
		}
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

	private record ParsedEvent(
		UUID eventId,
		UUID correlationId,
		UUID causationId,
		String tenantAlias,
		UUID runId,
		UUID attemptId,
		String requestId,
		String idempotencyKey,
		String snapshotHash,
		JsonNode payload
	) {
	}
}
